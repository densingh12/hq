package org.hyperic.hq.measurement.server.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.appdef.shared.AppdefEntityConstants;
import org.hyperic.hq.authz.server.session.Resource;
import org.hyperic.hq.authz.shared.AuthzConstants;
import org.hyperic.hq.authz.shared.ResourceManager;
import org.hyperic.hq.measurement.MeasurementConstants;
import org.hyperic.hq.measurement.TimingVoodoo;
import org.hyperic.hq.measurement.shared.AvailabilityManager;
import org.hyperic.hq.product.MetricValue;



/**
 * Availability status checker for platforms which availability status data was not received for over 2 intervals.
 * <BR><B>Details:</B>
 * <BR> The checker receives a collection of DataPoints (latest availability status) for platforms that need rechecking.
 * <BR> It checks if there exists VC associations for the platform (if this platform status is given by a VM agent while
 * there also exists a VCented agent monitoring it). 
 * If so - update the status according to the status given by the VCenter agent.
 * <BR> If VC information exists and the Platform is UP - all its servers/services status is set as UNKNOWN.
 * <BR> If VC information does not exist, or is DOWN - all its servers/services status is set as DOWN.
 * <BR> Agent status is marked as DOWN in any case.
 * @author amalia
 *
 */
public class AvailabilityFallbackChecker {
	
	
	//TODO: (Code review comments)
	// Handle the case of 2 very different intervals. If the VM interval is 1 min, and the VC interval is 1 hr? Perhaps should limit the availability status validity to 5(?) times the VM interval.
	
	
    private final Log log = LogFactory.getLog(AvailabilityFallbackChecker.class);
    private final Object lock = new Object();

	private AvailabilityManager availabilityManager;
	private AvailabilityCache availabilityCache;
	private ResourceManager resourceManager;
	
	// For testing purposes, in case we need to perform checks with a constant timestamp.
	// if curTimeStamp is 0, we check for the actual current time. 
	private long curTimeStamp = 0;

	
	// --------------------------------------------------------------------------------------------------------
	// --------------------------------------------------------------------------------------------------------
	public AvailabilityFallbackChecker(AvailabilityManager availabilityManager, AvailabilityCache availabilityCache, ResourceManager resourceManager) {
		this.availabilityManager = availabilityManager;
		this.availabilityCache = availabilityCache;
		this.resourceManager = resourceManager;
	}
	

	// --------------------------------------------------------------------------------------------------------
	// --------------------------------------------------------------------------------------------------------
	
	/**
	 * Check platforms' availability with constant time stamp all through the check. Update DB/Cache accordingly.
	 * This means that if we use Ping checks (that may take several seconds), the timestamp will remain the same.
	 * Platforms' servers/services statuses may be updated as well.
	 * @param availabilityDataPoints - latest availability status for Platforms
	 * @param curTimeStamp - timestamp to use through the checks
	 */
	public void checkAvailability(Collection<ResourceDataPoint> availabilityDataPoints, long curTimeStamp) {
		this.curTimeStamp = curTimeStamp;
		checkAvailability(availabilityDataPoints);
		this.curTimeStamp = 0;		
	}

	
	/**
	 * Check platforms' availability. Update DB/Cache accordingly.
	 * Platforms' servers/services statuses may be updated as well.
	 * @param availabilityDataPoints
	 */
	public void checkAvailability(Collection<ResourceDataPoint> availabilityDataPoints) {
		if ((availabilityDataPoints == null) || (availabilityDataPoints.isEmpty()) )
				return;
		log.debug("checkAvailability: start");
		Collection<ResourceDataPoint> resPlatforms = new ArrayList<ResourceDataPoint>();
		for (ResourceDataPoint availabilityDataPoint : availabilityDataPoints) {
			ResourceDataPoint platformAvailPoint = checkPlatformAvailability(availabilityDataPoint);
			resPlatforms.add(platformAvailPoint);
		}
		log.info("checkAvailability: checking " + resPlatforms.size() + " platforms.");
		Collection<DataPoint> res = addStatusOfPlatformsDescendants(resPlatforms);
		log.info("checkAvailability: updating " + res.size() + " platforms & descendants.");
		storeUpdates(res);
	}

    public Object getLock() {
        return lock;
    }


    
    /**
     * check if the given Measurement belongs to an HQ Agent, and if so - mark it as down.
     * @param meas - Measurement of a checked server/service.
     * @return true if this is an HQAgent, false otherwise.
     */
	private boolean isHQAgent(Measurement meas) {
		try {
			Resource measResource = meas.getResource();
			//TODO remove the following line, and recheck
			measResource = resourceManager.getResourceById(measResource.getId());
			Resource prototype = measResource.getPrototype();
			if (prototype == null)
				return false;
			
			String prototypeName = prototype.getName();
			if (prototypeName.equals(AppdefEntityConstants.HQ_AGENT_PROTOTYPE_NAME)) {
				log.debug("isHQHagent:  Found: " + measResource.getId());
				return true;
			}
		} catch (Exception e) {
			log.warn(e.toString());
			log.warn("IsHQAgent: returning false.");
			return false;
		} 
		return false;
		
	}


	/**
	 * Store updates using availabilityManager
	 * @param availabilityDataPoints - calculated availability statuses 
	 */
	private void storeUpdates(Collection<DataPoint> availabilityDataPoints) {
		List<DataPoint> availDataPoints = new ArrayList<DataPoint>(availabilityDataPoints);
		this.availabilityManager.addData(availDataPoints, true, true);
	}

	/**
	 * Check availability for a single platform
	 * @param availabilityDataPoint - latest availability status
	 * @return new availability status to update
	 */
	private ResourceDataPoint checkPlatformAvailability(ResourceDataPoint availabilityDataPoint) {
		ResourceDataPoint res = getPlatformStatusFromVC(availabilityDataPoint);
		if (res != null)
			return res;
		res = getPlatformStatusByPing(availabilityDataPoint);
		if (res != null)
			return res;
		return availabilityDataPoint;
	}


	/**
	 * <B>Currently unimplemented. Exists for future usage.</B>
	 * @param availabilityDataPoint
	 * @return new availability status to update, if any status could be calculated
	 */
	@Deprecated
	private ResourceDataPoint getPlatformStatusByPing(ResourceDataPoint availabilityDataPoint) {
		// TODO Auto-generated method stub
		return null;
	}


	/**
	 * Check for availability status from VC information, if exists.
	 * VC information exists if this platform is monitored by a VM agent, and there also exists a VCenter agent that monitors this platform.
	 * @param availabilityDataPoint - latest availability status
	 * @return new availability status to update, if exists. Null otherwise.
	 */
	private ResourceDataPoint getPlatformStatusFromVC(ResourceDataPoint availabilityDataPoint) {
		log.debug("getPlatformStatusFromVC" );
		Integer platformId = availabilityDataPoint.getResource().getId();
		List<Integer> resourceIds = new ArrayList<Integer>();
		resourceIds.add(platformId);
		final Map<Integer, List<Measurement>> virtualParent = availabilityManager.getAvailMeasurementParent(
				resourceIds, AuthzConstants.ResourceEdgeVirtualRelation);
		if (isEmptyMap(virtualParent)) {
			return null;
		}
		// else - there should be a single measurement of the related VM Instance:
		List<Measurement> resourceEdgeVirtualRelations = virtualParent.get(platformId);
		if ((resourceEdgeVirtualRelations == null) | (resourceEdgeVirtualRelations.isEmpty()) )
		{
			return null;
		}
		if (resourceEdgeVirtualRelations.size() != 1) {
			log.warn("getPlatfromStatusFromVC: Platform " + platformId + " got " + resourceEdgeVirtualRelations.size() + " virtual parents. Ignoring.");
			return null;
		}
		// we now have the VM Instance Measurement ID. We will copy its latest availability status
		Measurement vmParentMeasurement = resourceEdgeVirtualRelations.get(0);
		long endTimeStamp = getEndWindow(getCurTimestamp(), vmParentMeasurement);
        final DataPoint defaultParentDataPoint = new DataPoint(vmParentMeasurement.getId().intValue(), MeasurementConstants.AVAIL_NULL, endTimeStamp);
        final DataPoint lastParentDataPoint = availabilityCache.get(vmParentMeasurement.getId(), defaultParentDataPoint);
        if (lastParentDataPoint == null)
        	return null;
        double parentStatus = lastParentDataPoint.getValue();
        if ((parentStatus == MeasurementConstants.AVAIL_UP) || (parentStatus == MeasurementConstants.AVAIL_DOWN)) {
        	DataPoint newDataPoint = new DataPoint(availabilityDataPoint.getMeasurementId(), lastParentDataPoint.getMetricValue());
        	ResourceDataPoint resPoint = new ResourceDataPoint(availabilityDataPoint.getResource(), newDataPoint);
    		log.info("getPlatformStatusFromVC: found parent measurement: " + lastParentDataPoint.getMeasurementId() + "; adding point: " + resPoint.toString());
        	return resPoint;
        }

		return null;
	}
	
	private boolean isEmptyMap(Map<Integer, List<Measurement>> rHierarchy) {
		if (rHierarchy == null)
			return true;
		if (rHierarchy.size() ==0)
			return true;
		if (rHierarchy.isEmpty())
			return true;
		return false;
	}


	/**
	 * Given a list of platforms' data points, return a collection of datapoints of platforms' servers an services,
	 * with their appropriate status.
	 * @param checkedPlatforms - new calculated availability status of platforms.
	 * @return collection of statuses of the platforms' servers an services.
	 */
	private Collection<DataPoint> addStatusOfPlatformsDescendants(Collection<ResourceDataPoint> checkedPlatforms) {
		log.debug("addStatusOfPlatformsDescendants: start" );
		final Collection<DataPoint> res = new ArrayList<DataPoint>();
		final List<Integer> resourceIds = new ArrayList<Integer>();
		for (ResourceDataPoint rDataPoint : checkedPlatforms) {
			resourceIds.add(rDataPoint.getResource().getId());
		}
		final Map<Integer, List<Measurement>> rHierarchy = availabilityManager.getAvailMeasurementChildren(
				resourceIds, AuthzConstants.ResourceEdgeContainmentRelation);
		for (ResourceDataPoint rdp : checkedPlatforms) {
			final Resource platform = rdp.getResource();
			res.add(rdp);
			final List<Measurement> associatedResources = rHierarchy.get(platform.getId());
			if (associatedResources == null) {
				continue;
			}

			double assocStatus = MeasurementConstants.AVAIL_DOWN;
			if (rdp.getMetricValue().getValue() == MeasurementConstants.AVAIL_UP) {
				assocStatus = MeasurementConstants.AVAIL_UNKNOWN;
			}
			
			for (Measurement meas : associatedResources) {

				if (!meas.isEnabled()) {
					continue;
				}
				double curStatus = assocStatus;
				if (isHQAgent(meas))
					curStatus = MeasurementConstants.AVAIL_DOWN;
				
				final long curTimeStamp = getCurTimestamp();
				final long backfillTime = getBackfillTime(curTimeStamp, meas);
				if (backfillTime > curTimeStamp) {
					// the resource was updated during the last interval. we do not want to update it.
					// TODO: Shouldn't platform be marked as UP?
					continue;
				}
				final MetricValue val = new MetricValue(curStatus, backfillTime);
				final MeasDataPoint point = new MeasDataPoint(meas.getId(), val, true);
				res.add(point);
			}
		}
		log.debug("addStatusOfPlatformsDescendants: end, res size: " + res.size() );
		return res;
	}
	
	/**
	 * get the time of the first interval that was not updated.
	 * @param current - current time stamp
	 * @param meas - measurement to check for
	 * @return the time of the first interval that was not updated.
	 */
	private long getBackfillTime(long current, Measurement meas) {
		final long end = getEndWindow(current, meas);
		final DataPoint defaultPt = new DataPoint(meas.getId()
				.intValue(), MeasurementConstants.AVAIL_NULL, end);
		final DataPoint lastPt = availabilityCache.get(meas.getId(),
				defaultPt);
		final long backfillTime = lastPt.getTimestamp() + meas.getInterval();
		return backfillTime;
	}
	
    // End is at least more than 1 interval away
    private long getEndWindow(long current, Measurement meas) {
        return TimingVoodoo.roundDownTime((current - meas.getInterval()), meas.getInterval());
    }


	
	/**
	 * if curTimeStamp is 0, return the real current time.
	 * Otherwise - return curTimeStamp set by the calling method.
	 * @return time
	 */
    private long getCurTimestamp() {
    	if (this.curTimeStamp != 0)
    		return this.curTimeStamp;
        return System.currentTimeMillis();
    }


}
