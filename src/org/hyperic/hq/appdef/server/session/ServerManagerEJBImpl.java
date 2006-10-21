/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
 * This file is part of HQ.
 * 
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.appdef.server.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ejb.CreateException;
import javax.ejb.FinderException;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.naming.NamingException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.appdef.shared.AppdefDuplicateNameException;
import org.hyperic.hq.appdef.shared.AppdefEntityConstants;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEvent;
import org.hyperic.hq.appdef.shared.ApplicationNotFoundException;
import org.hyperic.hq.appdef.shared.MiniResourceValue;
import org.hyperic.hq.appdef.shared.PlatformNotFoundException;
import org.hyperic.hq.appdef.shared.PlatformPK;
import org.hyperic.hq.appdef.shared.PlatformTypePK;
import org.hyperic.hq.appdef.shared.PlatformTypeValue;
import org.hyperic.hq.appdef.shared.ServerLightValue;
import org.hyperic.hq.appdef.shared.ServerNotFoundException;
import org.hyperic.hq.appdef.shared.ServerPK;
import org.hyperic.hq.appdef.shared.ServerTypePK;
import org.hyperic.hq.appdef.shared.ServerTypeValue;
import org.hyperic.hq.appdef.shared.ServerVOHelperUtil;
import org.hyperic.hq.appdef.shared.ServerValue;
import org.hyperic.hq.appdef.shared.ServiceNotFoundException;
import org.hyperic.hq.appdef.shared.UpdateException;
import org.hyperic.hq.appdef.shared.ValidationException;
import org.hyperic.hq.appdef.Service;
import org.hyperic.hq.appdef.ServiceType;
import org.hyperic.hq.appdef.Server;
import org.hyperic.hq.appdef.ServerType;
import org.hyperic.hq.appdef.PlatformType;
import org.hyperic.hq.appdef.Platform;
import org.hyperic.hq.appdef.AppService;
import org.hyperic.hq.appdef.Application;
import org.hyperic.hq.authz.shared.AuthzConstants;
import org.hyperic.hq.authz.shared.AuthzSubjectValue;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.PermissionManager;
import org.hyperic.hq.authz.shared.PermissionManagerFactory;
import org.hyperic.hq.authz.shared.ResourceValue;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.shared.HQConstants;
import org.hyperic.hq.product.ServerTypeInfo;
import org.hyperic.util.ArrayUtil;
import org.hyperic.util.jdbc.DBUtil;
import org.hyperic.util.pager.PageControl;
import org.hyperic.util.pager.PageList;
import org.hyperic.util.pager.Pager;
import org.hyperic.util.pager.SortAttribute;
import org.hyperic.hibernate.dao.ServerDAO;
import org.hyperic.hibernate.dao.ConfigResponseDAO;
import org.hyperic.hibernate.dao.ServerTypeDAO;
import org.hyperic.hibernate.dao.PlatformTypeDAO;
import org.hyperic.hibernate.dao.ApplicationDAO;
import org.hyperic.dao.DAOFactory;
import org.hibernate.ObjectNotFoundException;

/**
 * This class is responsible for managing Server objects in appdef
 * and their relationships
 * @ejb:bean name="ServerManager"
 *      jndi-name="ejb/appdef/ServerManager"
 *      local-jndi-name="LocalServerManager"
 *      view-type="local"
 *      type="Stateless"
 * @ejb:util generate="physical"
 * @ejb:transaction type="SUPPORTS"
 */
public class ServerManagerEJBImpl extends AppdefSessionEJB
    implements SessionBean {

    private Log log = LogFactory.getLog(
        "org.hyperic.hq.appdef.server.session.ServerManagerEJBImpl");

    private final String VALUE_PROCESSOR
        = "org.hyperic.hq.appdef.server.session.PagerProcessor_server";
    private Pager valuePager = null;
    private final Integer APPDEF_RES_TYPE_UNDEFINED = new Integer(-1);

    private static final PermissionManager pm = 
        PermissionManagerFactory.getInstance();

    private Connection getDBConn() throws SQLException {
        try {
            return DBUtil.getConnByContext(this.getInitialContext(), 
                                            HQConstants.DATASOURCE);
        } catch(NamingException exc){
            throw new SystemException("Unable to get database context: " +
                                         exc.getMessage(), exc);
        }
    }

    /**
     * Create a Server which runs on a given platform
     * @param PlatformPK - the pk of the platform hosting the server
     * @param ServerTypePK - the pk of the server type
     * @param ServerValue - the value object representation of the server
     * @return ServerValue - the saved value object
     * @exception CreateException - if it fails to add the server
     * @ejb:interface-method
     * @ejb:transaction type="RequiresNew"
     */
    public ServerPK createServer(AuthzSubjectValue subject, PlatformPK ppk,
                                    ServerTypePK stpk, ServerValue sValue)
        throws CreateException, ValidationException, PermissionException,
               PlatformNotFoundException, AppdefDuplicateNameException {
        if(log.isDebugEnabled()) {
            log.debug("Begin createServer: " + sValue);
        }

        try {
            validateNewServer(sValue);
            trimStrings(sValue);
            // first we look up the platform
            // if this bombs we go no further
            Platform pLocal = findPlatformByPK(ppk);
            ServerTypeValue serverType = null;
            try {
                serverType = ServerVOHelperUtil.getLocalHome()
                    .create().getServerTypeValue(stpk);
                // set the serverTypeValue
                sValue.setServerType(serverType);
            } catch (CreateException e) {
                throw new SystemException(e);    
            }
            // set the owner
            sValue.setOwner(subject.getName());
            // set modified by
            sValue.setModifiedBy(subject.getName());
            // call the create
            Server server = getServerDAO().createServer(pLocal, sValue);
            // now do authz check
            createAuthzServer(sValue.getName(), 
                              server.getId(),
                              ppk.getId(), 
                              serverType.getVirtual(), 
                              subject);
            // remove platform vo from the cache
            // since the server set has changed
            VOCache.getInstance().removePlatform(ppk.getId());
            return server.getPrimaryKey();
        } catch (CreateException e) {
            throw e;
        } catch (PermissionException e) {
            // rollback the transaction if no permission to create
            // a server; otherwise, a server record gets created without its
            // corresponding resource record.
            throw e;
        } catch (FinderException e) {
            throw new CreateException("Unable to find server type: "
                                      + ppk + " : " + e.getMessage());
        } catch (NamingException e) {
            throw new SystemException("Unable to get LocalHome " +
                                         e.getMessage());
        }
    }

    /**
     * Remove a server
     * @param subject - who
     * @param id - the id of the server
     * @param deep - whether to delete subobjects
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRED"
     */
    public void removeServer(AuthzSubjectValue subject, Integer id,
                             boolean deep) 
        throws ServerNotFoundException, RemoveException, PermissionException {

        Server server;
        // find it
        try {
            server = getServerDAO().findById(id);
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException(id);
        }
        removeServer(subject, server, deep);
    }

    /**
     * A removeServer method that takes a ServerLocal.  Used by
     * PlatformManager.removePlatform when cascading removal to servers.
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRED"
     */
    public void removeServer (AuthzSubjectValue subject,
                              Server server, boolean deep)
        throws ServerNotFoundException, RemoveException, PermissionException {
        ServerPK pk = (ServerPK)server.getPrimaryKey();
        Integer id = pk.getId();
        try {
            // check to see if there are services installed on the
            // server
            Collection services = server.getServices();
            int numServices = services.size();
            if(numServices > 0 && !deep) {
                throw new RemoveException("Server " + server.getName() 
                    + " has " + numServices + " services");
            }
            // check if the user has permission to remove it
            // user needs the removeServer permission on the server
            // to succeed
            ResourceValue rv = this.getServerResourceValue(pk);
            checkRemovePermission(subject, server.getEntityId());
            // remove the service resource entries and validate permissions
            Iterator servicesIt = services.iterator();
            while(servicesIt.hasNext()) {
                Service aService = (Service)servicesIt.next();
                getServiceMgrLocal().removeService(subject, aService, true);
                /*
                ResourceValue serviceRv = getServiceResourceValue(
                    (ServicePK)aService.getPrimaryKey());
                checkRemovePermission(subject, aService.getEntityId());
                this.removeAuthzResource(subject, serviceRv);
                VOCache.getInstance()
                    .removeService(((ServicePK)aService.getPrimaryKey()).getId());
                */
            }

            // keep the configresponseId so we can remove it later
            Integer cid = server.getConfigResponseId();

            // remove the resource
            this.removeAuthzResource(subject, rv);
            // remove the server and platform vo's from the cache
            VOCache.getInstance().removeServer(id);
            VOCache.getInstance().removePlatform(server.getPlatform().getId());
            // remove it
            getServerDAO().remove(server);

            // remove the config response
            if (cid != null) {
                try {
                    ConfigResponseDAO cdao =
                        DAOFactory.getDAOFactory().getConfigResponseDAO();
                    cdao.remove(cdao.findById(cid));
                } catch (ObjectNotFoundException e) {
                    // OK, no config response, just log it
                    log.warn("Invalid config ID " + cid);
                }
            }

            // remove custom properties
            deleteCustomProperties(AppdefEntityConstants.APPDEF_TYPE_SERVER, 
                                   pk.getId().intValue());

            // Send server deleted event
            sendAppdefEvent(subject, new AppdefEntityID(pk),
                            AppdefEvent.ACTION_DELETE);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            throw new ServerNotFoundException(id, e);
        }
    }

    /**
     * Create a ServerType which is supported by a specific set of platform Types
     * @param ServerTypeValue   
     * @param platformTypeList 
     * @return ServerTypeValue
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRESNEW"
     */
    public ServerTypePK createServerType(AuthzSubjectValue subject,
                                            ServerTypeValue stv,
                                            List suppPlatTypes)
        throws CreateException, ValidationException {
        try {
            if(log.isDebugEnabled()) {
                log.debug("Begin createServerType: " + stv);
            }
            validateNewServerType(stv, suppPlatTypes);
            // now we create the serverType 
            ServerTypeDAO stLHome = getServerTypeDAO();
            ServerType sType = stLHome.create(stv);
            // now we need to add the platform types
            HashSet ptSet = new HashSet();
            for(int i=0;i<suppPlatTypes.size(); i++) {
                PlatformTypeValue ptv =(PlatformTypeValue)suppPlatTypes.get(i);
                // now find the ejb
                PlatformType pType =
                    DAOFactory.getDAOFactory().getPlatformTypeDAO()
                    .findByPrimaryKey(ptv.getPrimaryKey());
                // and add it to the set
                ptSet.add(pType);
                // flush the platform type from the cache
                VOCache.getInstance().removePlatformType(ptv.getId());
            }
            // now we add the set to the server type
            sType.setPlatformTypes(ptSet);
            
            // and finally, return the primary key
            return sType.getPrimaryKey();
        } catch (ObjectNotFoundException e) {
            throw new CreateException("Failed to look up a PlatformType: " +
                                      e.getMessage());
        }
    }

    /**
     * Find all server types
     * @return list of serverTypeValues
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getAllServerTypes(AuthzSubjectValue subject,
                                      PageControl pc)
        throws FinderException {

        Collection serverTypes = getServerTypeDAO().findAll();

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverTypes, pc);
    }
        
    /**
     * Find viewable server types
     * @return list of serverTypeValues
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getViewableServerTypes(AuthzSubjectValue subject,
                                      PageControl pc)
        throws FinderException, PermissionException {

        // build the server types from the visible list of servers
        Collection servers;
        servers = getViewableServers(subject, pc);

        Collection serverTypes = filterResourceTypes(servers);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverTypes, pc);
    }
    
    /**
     * Find viewable server non-virtual types for a platform
     * @return list of serverTypeValues
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServerTypesByPlatform(AuthzSubjectValue subject,
                                             Integer platId,
                                             PageControl pc)
        throws PermissionException, PlatformNotFoundException, 
               ServerNotFoundException {    
        return this.getServerTypesByPlatform(subject, platId, true, pc);
    }
    /**
     * Find viewable server types for a platform
     * @return list of serverTypeValues
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public PageList getServerTypesByPlatform(AuthzSubjectValue subject,
                                             Integer platId,
                                             boolean excludeVirtual,
                                             PageControl pc)
        throws PermissionException, PlatformNotFoundException, 
               ServerNotFoundException {

        // build the server types from the visible list of servers
        Collection servers;
        servers = getServersByPlatformImpl(subject, 
                                           platId, 
                                           this.APPDEF_RES_TYPE_UNDEFINED,
                                           excludeVirtual,
                                           pc);

        Collection serverTypes = filterResourceTypes(servers);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverTypes, pc);
    }
    
    /**
     * Find Server by id
     * @param subject - who
     * @param id - id of server
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServerValue findServerById(AuthzSubjectValue subject,
                                      Integer id) 
        throws ServerNotFoundException {

        ServerValue server;
        try {
            server = ServerVOHelperUtil.getLocalHome().create()
                .getServerValue(new ServerPK(id));
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            throw new ServerNotFoundException(id, e);
        }
        return server;

    }

    /**
     * Find servers by name
     * @param subject - who
     * @param name - name of server
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServerValue[] findServersByName(AuthzSubjectValue subject,
                                           String name)
        throws ServerNotFoundException
    {
        try {
            List serverLocals = getServerDAO().findByName(name);

            int numServers = serverLocals.size();
            if (numServers == 0) {
                throw new ServerNotFoundException("Server '" +
                                                  name + "' not found");
            }

            List servers = new ArrayList();
            for (int i = 0; i < numServers; i++) {
                Server sLocal = (Server)serverLocals.get(i);
                ServerValue sValue = ServerVOHelperUtil.getLocalHome().
                    create().getServerValue(sLocal);
                try {
                    checkViewPermission(subject, sValue.getEntityId());
                    servers.add(sValue);
                } catch (PermissionException e) {
                    //Ok, won't be added to the list
                }
            }

            return (ServerValue[])servers.toArray(new ServerValue[0]);

        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
    }

    /**
     * Find a server type by id
     * @param id - The ID of the server
     * @return ServerTypeValue
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServerTypeValue findServerTypeById(Integer id)
        throws FinderException {
        ServerTypeValue sTypeV;
        try {
            sTypeV = ServerVOHelperUtil.getLocalHome().create().getServerTypeValue(new ServerTypePK(id));
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
        return sTypeV;
    }

    /**
     * Find a server type by name
     * @param name - the name of the server
     * @return ServerTypeValue
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServerTypeValue findServerTypeByName(String name)
        throws FinderException {
        try {
            ServerType ejb = getServerTypeDAO().findByName(name);
            if (ejb == null) {
                throw new FinderException("name not found: " + name);
            }
            return ServerVOHelperUtil.getLocalHome().create().getServerTypeValue(ejb);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
    }

    /** 
     * Get server lite value by id.  Does not check permission.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @param Integer id
     */
    public ServerLightValue getServerLightValue(Integer id)
        throws ServerNotFoundException {
        try {
            ServerDAO serverLocalHome = getServerDAO();
            Server server =
                serverLocalHome.findByPrimaryKey(new ServerPK(id));
            return server.getServerLightValue();
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException(id, e);
        }
    }

    /**
     * Get server IDs by server type.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @return An array of Server IDs.
     */
    public Integer[] getServerIds(AuthzSubjectValue subject,
                                  Integer servTypeId)
        throws PermissionException {
        ServerDAO sLHome;
        try {
            sLHome = getServerDAO();
            Collection servers = sLHome.findByType(servTypeId);
            if (servers.size() == 0) {
                return new Integer[0];
            }
            List serverIds = new ArrayList(servers.size());
         
            // now get the list of PKs
            Collection viewable = super.getViewableServers(subject);
            // and iterate over the ejbList to remove any item not in the
            // viewable list
            int i = 0;
            for (Iterator it = servers.iterator(); it.hasNext(); i++) {
                Server aEJB = (Server) it.next();
                if (viewable.contains(aEJB.getPrimaryKey())) {
                    // add the item, user can see it
                    serverIds.add(aEJB.getId());
                }
            }
        
            return (Integer[]) serverIds.toArray(new Integer[0]);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            // There are no viewable servers
            return new Integer[0];
        }
    }

    /** 
     * Get server by id.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @param Integer id
     */
    public ServerValue getServerById(AuthzSubjectValue subject, 
                                     Integer id)
        throws ServerNotFoundException, PermissionException {
        try {
            ServerValue serverValue = ServerVOHelperUtil.getLocalHome()
                .create().getServerValue(new ServerPK(id));
            // now check if the user can see this at all
            // one would think that it makes more sense to check the
            // permission first, but if the resource doesnt exist, the
            // check will falsely report a permission exception, instead
            // of a finder exception
            checkViewPermission(subject, serverValue.getEntityId());
            return serverValue;
        } catch (FinderException e) {
            throw new ServerNotFoundException(id, e);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
    }

    /**
     * Get servers by name.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @param Subject - who
     * @param name - name of server
     */
    public ServerValue[] getServersByName(AuthzSubjectValue subject, String name)
        throws ServerNotFoundException {

        return findServersByName(subject, name);
    }

    /**
     * Get server by service.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @param Subject - who
     * @param service - Service ID for which to fetch the server
     */
    public ServerValue getServerByService(AuthzSubjectValue subject,
                                          Integer sID) 
        throws ServerNotFoundException, ServiceNotFoundException, 
               PermissionException
    {
        Service serviceLocal;
        ServerValue serverValue;
        try {
            serviceLocal = getServiceDAO().findById(sID);
            
            serverValue = ServerVOHelperUtil.getLocalHome().create()
                .getServerValue(serviceLocal.getServer().getPrimaryKey());
            
            // Make sure they're authorized to see it. Otherwise, we should
            // throw a not-found exception.
            checkViewPermission(subject, serverValue.getEntityId());
        } catch (FinderException e) {
            throw new ServiceNotFoundException(sID, e);
        } catch (ObjectNotFoundException e) {
            throw new ServiceNotFoundException(sID, e);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (CreateException e) {
            throw new SystemException(e);
        }
        return serverValue;
    }

    /**
     * Get server by service.  The virtual servers are not filtere out of
     * returned list.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     * @param Subject - who
     * @param service - Service ID for which to fetch the server
     * @throws ServerNotFoundException
     */
    public PageList getServersByServices(AuthzSubjectValue subject, List sIDs) 
        throws PermissionException, ServerNotFoundException {
        List authzPks;
        try {
            authzPks = getViewableServers(subject);
        } catch(FinderException exc){
            return new PageList();
        } catch (NamingException e) {
            throw new SystemException(e);
        }

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String sql =
            "SELECT DISTINCT(server_id) FROM EAM_SERVICE WHERE " +
            DBUtil.composeConjunctions("id", sIDs.size());

        HashSet serverIds = new HashSet();
        
        try {
            conn = getDBConn();
            ps = conn.prepareStatement(sql);
            
            int i = 1;
            for (Iterator it = sIDs.iterator(); it.hasNext(); ) {
                AppdefEntityID svcId = (AppdefEntityID) it.next();
                if (svcId.getType() !=
                    AppdefEntityConstants.APPDEF_TYPE_SERVICE)
                    throw new ServerNotFoundException("Entity not a service " +
                                                      svcId);
                ps.setInt(i++, svcId.getID());
            }
            
            rs = ps.executeQuery();

            while (rs.next()) {
                serverIds.add(new ServerPK(new Integer(rs.getInt(1))));
            }
        } catch (SQLException e) {
            throw new SystemException("Error looking up services by id: " + e,
                                      e);
        } finally {
            DBUtil.closeJDBCObjects(log, conn, ps, rs);
        }

        ArrayList servers = new ArrayList();
        for (Iterator it = serverIds.iterator(); it.hasNext();) {
            ServerPK pk = (ServerPK) it.next();
            if(!authzPks.contains(pk))
                continue;
            
            try {
                Server server = getServerDAO().findByPrimaryKey(pk);
                servers.add(server);
            } catch (ObjectNotFoundException e) {
                continue;
            }
        }
        
        return valuePager.seek(servers, null);
    }


    /**
     * Get all servers.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param PageControl  
     * @return A List of ServerValue objects representing all of the
     * servers that the given subject is allowed to view.
     */
    public PageList getAllServers ( AuthzSubjectValue subject,
                                    PageControl pc) 
        throws FinderException, PermissionException {
        Collection servers = null;
        servers = getViewableServers(subject, pc);
        
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(servers, pc);
    }

    /**
     * Get the scope of viewable servers for a given user
     * @param subject - the user
     * @return List of ServerLocals for which subject has 
     * AuthzConstants.serverOpViewServer
     */
    private Collection getViewableServers(AuthzSubjectValue subject,
                                          PageControl pc)
        throws PermissionException, FinderException {
        Collection servers;
        try {
            List authzPks = getViewableServers(subject);
            int attr = -1;
            if (pc != null) {
                attr = pc.getSortattribute();
            }
            switch(attr) {
                case SortAttribute.RESOURCE_NAME:
                    servers = getServerDAO().findAll_orderName(
                        pc != null ? !pc.isDescending() : true);
                    break;
                default:
                    servers = getServerDAO().findAll_orderName(true);
                    break;
            }
            for(Iterator i = servers.iterator(); i.hasNext();) {
                ServerPK sPK = ((Server)i.next()).getPrimaryKey();
                // remove server if its not viewable
                if(!authzPks.contains(sPK)) {
                    i.remove();
                }
            }
            return servers;
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    private Collection getServersByPlatformImpl( AuthzSubjectValue subject,
                                                 Integer platId,
                                                 Integer servTypeId,
                                                 boolean excludeVirtual,
                                                 PageControl pc)
        throws PermissionException, ServerNotFoundException, 
               PlatformNotFoundException {
        List authzPks;
        try {
            authzPks = getViewableServers(subject);
        } catch(FinderException exc){
            throw new ServerNotFoundException(
                "No (viewable) servers associated with platform " + platId);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
        
        List servers;
        // first, if they specified a server type, then filter on it
        if(servTypeId != APPDEF_RES_TYPE_UNDEFINED) {
            if(!excludeVirtual) {
                servers = getServerDAO()
                    .findByPlatformAndType_orderName(platId, servTypeId);
            } else {
                servers = getServerDAO()
                    .findByPlatformAndType_orderName(platId, servTypeId,
                                                     Boolean.FALSE);
            }
        }
        else {
            if(!excludeVirtual) {
                servers = getServerDAO()
                    .findByPlatform_orderName(platId);
            } else {
                servers = getServerDAO()
                    .findByPlatform_orderName(platId, Boolean.FALSE);
            }
        }
        if (servers.size() == 0) {
            throw new PlatformNotFoundException(platId);
        }
        for(Iterator i = servers.iterator(); i.hasNext();) {
            Server aServer = (Server)i.next();
            
            // Keep the virtual ones, we need them so that child services can be
            // added.  Otherwise, no one except the super user will have access
            // to the virtual services
            if (aServer.getServerType().getVirtual())
                continue;
            
            // Remove the server if its not viewable
            if (!authzPks.contains(aServer.getPrimaryKey())) {
                i.remove();
            }
        } 
    
        // If sort descending, then reverse the list
        if(pc != null && pc.isDescending()) {
            Collections.reverse(servers);
        }
        
        return servers;
    }

    /**
     * Get servers by platform.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param platId platform id.
     * @param excludeVirtual true if you dont want virtual (fake container) servers
     * in the returned list
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     * specified platform that the subject is allowed to view.
     */
    public PageList getServersByPlatform ( AuthzSubjectValue subject,
                                           Integer platId,
                                           boolean excludeVirtual,
                                           PageControl pc) 
        throws ServerNotFoundException, PlatformNotFoundException,
               PermissionException 
    {
        return this.getServersByPlatform(subject, 
                                         platId,
                                         this.APPDEF_RES_TYPE_UNDEFINED, 
                                         excludeVirtual,
                                         pc);
    }

    /**
     * Get servers by server type and platform.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     * specified platform that the subject is allowed to view.
     */
    public PageList getServersByPlatform ( AuthzSubjectValue subject,
                                           Integer platId,
                                           Integer servTypeId,
                                           boolean excludeVirtual,
                                           PageControl pc) 
        throws ServerNotFoundException, PlatformNotFoundException, 
               PermissionException 
    {
        Collection servers =
            getServersByPlatformImpl(subject, platId, servTypeId, excludeVirtual, pc);
        
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(servers, pc);
    }

    /**
     * Get servers by server type and platform.
     * @ejb:interface-method
     *
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     * specified platform that the subject is allowed to view.
     * @ejb:transaction type="Required"
     */
    public PageList getServersByPlatformServiceType( AuthzSubjectValue subject,
                                                     Integer platId,
                                                     Integer svcTypeId)
        throws ServerNotFoundException, PlatformNotFoundException, 
               PermissionException {
        PageControl pc = PageControl.PAGE_ALL;
        Integer servTypeId;
        try {
            ServiceType typeV = getServiceTypeDAO().findById(svcTypeId);
            servTypeId = typeV.getServerType().getId();
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException("Service Type not found", e);
        }
        
        Collection servers =
            getServersByPlatformImpl(subject, platId, servTypeId, false, pc);
        
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(servers, pc);
    }
    
    /**
     * Get servers by server type and platform.
     * @ejb:interface-method
     * @param subject The subject trying to list servers.
     * @param typeId server type id.
     *
     * @return A PageList of ServerValue objects representing servers on the
     * specified platform that the subject is allowed to view.
     * @ejb:transaction type="Required"
     */
    public List getServersByType( AuthzSubjectValue subject, Integer typeId)
        throws PermissionException {
        try {
            Collection servers = getServerDAO().findByType(typeId);

            List authzPks = getViewableServers(subject);        
            for(Iterator i = servers.iterator(); i.hasNext();) {
                ServerPK sPK = ((Server) i.next()).getPrimaryKey();
                // remove server if its not viewable
                if(!authzPks.contains(sPK))
                    i.remove();
            }
            
            // valuePager converts local/remote interfaces to value objects
            // as it pages through them.
            return valuePager.seek(servers, PageControl.PAGE_ALL);
        } catch (FinderException e) {
            return new ArrayList(0);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    private final String SQL_SERVER_BY_ID =
        "SELECT RES.ID AS RID, S.ID, T.NAME AS TNAME, S.NAME, S.CTIME " +
        "FROM EAM_SERVER S, EAM_SERVER_TYPE T, EAM_RESOURCE RES " + 
        PermissionManager.AUTHZ_FROM + " " +
        "WHERE RES.INSTANCE_ID = S.ID " +
        "AND RES.RESOURCE_TYPE_ID = " + AuthzConstants.authzServer + " " +
        "AND S.SERVER_TYPE_ID =T.ID " +
        "AND S.ID = ? ";
    
    /**
     * Get a server by id.
     *
     * Unlike it's value object counterpart this method will not throw 
     * permission exceptions, just ServerNotFoundException.
     *
     * @ejb:transaction type="Required"
     * @ejb:interface-method
     * @param Integer id
     */
    public MiniResourceValue getMiniServerById(AuthzSubjectValue subject,
                                               Integer id)
        throws ServerNotFoundException
    {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String sql =
            SQL_SERVER_BY_ID +
            pm.getSQLWhere(subject.getId(), "S.ID");

        try {
            conn = getDBConn();
            ps = conn.prepareStatement(sql);
            
            ps.setInt(1, id.intValue());
            pm.prepareSQL(ps, 2, 
                          subject.getId(),
                          AuthzConstants.authzServer,
                          AuthzConstants.perm_viewServer);

            rs = ps.executeQuery();

            if (rs.next()) {
                int col = 1;
                
                MiniResourceValue val =
                    new MiniResourceValue(rs.getInt(col++),
                                          rs.getInt(col++),
                                          AppdefEntityConstants.
                                          APPDEF_TYPE_SERVER,
                                          rs.getString(col++),
                                          rs.getString(col++),
                                          rs.getLong(col++));

                return val;
            } else {
                // XXX: Could retry the query here without the authz to
                //      see if it's a permissions issue.
                throw new ServerNotFoundException("Server " + id +
                                                  " not found");
            }
        } catch (SQLException e) {
            throw new SystemException("Error looking up server by id: " +
                                         e, e);
        } finally {
            DBUtil.closeJDBCObjects(log, conn, ps, rs);
        }
    }

    private static final String SQL_SERVER_BY_SERVICE =
        "SELECT RES.ID AS RID, S.ID, T.NAME AS TNAME, S.NAME, S.CTIME " +
        "FROM EAM_SERVER S, EAM_SERVER_TYPE T, EAM_SERVICE SVC, " +
        "EAM_RESOURCE RES " + 
        PermissionManager.AUTHZ_FROM + " " +
        "WHERE RES.INSTANCE_ID = S.ID " +
        "AND RES.RESOURCE_TYPE_ID = " + AuthzConstants.authzServer + " " +
        "AND S.ID = SVC.SERVER_ID " +
        "AND S.SERVER_TYPE_ID = T.ID " +
        "AND SVC.ID = ? ";

    /**
     * Get a server by service.
     *
     * @ejb:transaction type="Required"
     * @ejb:interface-method
     * @param Integer id
     */
    public MiniResourceValue getMiniServerByService(AuthzSubjectValue subject,
                                                    Integer id)
        throws ServiceNotFoundException {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = getDBConn();
            ps = conn.prepareStatement(SQL_SERVER_BY_SERVICE);
            
            ps.setInt(1, id.intValue());

            rs = ps.executeQuery();

            if (rs.next()) {
                int col = 1;
                
                MiniResourceValue val =
                    new MiniResourceValue(rs.getInt(col++),
                                          rs.getInt(col++),
                                          AppdefEntityConstants.
                                          APPDEF_TYPE_SERVER,
                                          rs.getString(col++),
                                          rs.getString(col++),
                                          rs.getLong(col++));

                return val;
            } else {
                // XXX: Could retry the query here without the authz to
                //      see if it's a permissions issue.
                if (log.isDebugEnabled()) {
                    log.debug(SQL_SERVER_BY_SERVICE);
                    log.debug("arg1: " + id);
                }
                throw new ServiceNotFoundException("Service " + id +
                                                   " not found");
            }
        } catch (SQLException e) {
            throw new SystemException("Error looking up server by " +
                                         "service: " + e, e);
        } finally {
            DBUtil.closeJDBCObjects(log, conn, ps, rs);
        }
    }

    private static final String SQL_SERVERS_BY_PLATFORM =
        "SELECT RES.ID AS RID, S.ID, T.NAME AS TNAME, S.NAME, S.CTIME " +
        "FROM EAM_SERVER S, EAM_RESOURCE RES, " +
        "EAM_SERVER_TYPE T " + PermissionManager.AUTHZ_FROM + " " +
        "WHERE RES.INSTANCE_ID = S.ID " +
        "AND S.SERVER_TYPE_ID = T.ID " +
        "AND RES.RESOURCE_TYPE_ID = " + AuthzConstants.authzServer + " " +
        "AND S.PLATFORM_ID = ? ";
    /**
     * Get servers by platform.
     *
     * Unlike it's value object counterpart this method will not throw 
     * permission exceptions, just PlatformNotFoundException.  This method
     * is also not capable of filtering on server types, though it would
     * be easy to add.  This method also excludes virtual server types.
     *
     * @ejb:transaction type="Required"
     * @ejb:interface-method
     */
    public PageList
        getMiniServersByPlatform(AuthzSubjectValue subject,
                                 Integer pid,
                                 PageControl pc)
        throws PlatformNotFoundException
    {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        String sqlOrder = "";
        int seekCount, i;

        // SQL string for page control ordering.
        int attr = pc.getSortattribute();

        switch(attr) {
        case SortAttribute.RESOURCE_NAME:
            if (pc != null && pc.isDescending()) {
                sqlOrder = " ORDER BY NAME DESC ";
            } else {
                sqlOrder = " ORDER BY NAME ";
            }
            break;
        case SortAttribute.CTIME:
            if (pc != null && pc.isDescending()) {
                sqlOrder = " ORDER BY CTIME DESC ";
            } else {
                sqlOrder = " ORDER BY CTIME ";
            }
        default:
            // No sorting
            break;
        }

        PageList servers = new PageList();

        try {
            conn = getDBConn();

            String sql = SQL_SERVERS_BY_PLATFORM +
                "AND T.FVIRTUAL = " + DBUtil.getBooleanValue(false, conn) +
                pm.getSQLWhere(subject.getId(), "S.ID") +
                sqlOrder;

            ps = conn.prepareStatement(sql);
            
            ps.setInt(1, pid.intValue());
            pm.prepareSQL(ps, 2, 
                          subject.getId(),
                          AuthzConstants.authzServer,
                          AuthzConstants.perm_viewServer);

            rs = ps.executeQuery();
            seekCount = DBUtil.seek(rs, pc);
            int pageSize = pc.getPagesize();
            boolean isUnlimited = (pageSize == PageControl.SIZE_UNLIMITED);
            for (i = 0; (isUnlimited || i<pageSize) && rs.next(); i++) {
                int col = 1;
                
                MiniResourceValue val =
                    new MiniResourceValue(rs.getInt(col++),
                                          rs.getInt(col++),
                                          AppdefEntityConstants.
                                          APPDEF_TYPE_SERVER,
                                          rs.getString(col++),
                                          rs.getString(col++),
                                          rs.getLong(col++));
                servers.add(val);
            } 
            int totalSize = DBUtil.countRows(seekCount+i, rs, conn);
            servers.setTotalSize(totalSize);

        } catch (SQLException e) {
            throw new SystemException("Error looking up servers by " +
                                         "platform: " + e, e);
        } finally {
            DBUtil.closeJDBCObjects(log, conn, ps, rs);
        }

        return servers;
    }

    /**
     * Get non-virtual server IDs by server type and platform.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     * specified platform that the subject is allowed to view.
     */
    public Integer[] getServerIdsByPlatform(AuthzSubjectValue subject,
                                            Integer platId, Integer servTypeId)
        throws ServerNotFoundException, PlatformNotFoundException, 
               PermissionException 
    {
        return getServerIdsByPlatform(subject, platId, servTypeId, true);
    }
    
    
    /**
     * Get server IDs by server type and platform.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     * specified platform that the subject is allowed to view.
     */
    public Integer[] getServerIdsByPlatform(AuthzSubjectValue subject,
                                            Integer platId, 
                                            Integer servTypeId,
                                            boolean excludeVirtual)
        throws ServerNotFoundException, PlatformNotFoundException, 
               PermissionException 
    {
        Collection servers =
            getServersByPlatformImpl(subject, platId, servTypeId, excludeVirtual, null);
        
        Integer[] ids = new Integer[servers.size()];
        Iterator it = servers.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Server server = (Server) it.next();
            ids[i] = server.getId();
        }
        
        return ids;
    }

    /**
     * Get servers by application and serverType.
     *
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @param pc The page control for this page list.
     * @return A List of ServerValue objects representing servers that support 
     * the given application that the subject is allowed to view.
     */
    private Collection getServersByApplicationImpl(AuthzSubjectValue subject,
                                                   Integer appId,
                                                   Integer servTypeId)
        throws ServerNotFoundException, ApplicationNotFoundException,
               PermissionException {
        Iterator it;
        List authzPks;
        Application appLocal;
        Collection appServiceCollection;
        HashMap serverCollection;
    
        try {
            ApplicationDAO appLocalHome = getApplicationDAO();
            
            try {
                appLocal = appLocalHome.findById(appId);
            } catch(ObjectNotFoundException exc){
                throw new ApplicationNotFoundException(appId, exc);
            }
            
            try {
                authzPks = getViewableServers(subject);
            } catch (FinderException e) {
                throw new ServerNotFoundException("No (viewable) servers " +
                                                  "associated with " +
                                                  "application " + appId, e);
            }
        } catch (NamingException e) {
            throw new SystemException(e);
        }
        
        serverCollection = new HashMap();
    
        // XXX - a better solution is to control the viewable set returned by
        //       ejbql finders. This will be forthcoming.
    
        appServiceCollection = appLocal.getAppServices();
        it = appServiceCollection.iterator();
    
        while (it.hasNext()) {
    
            AppService appService = (AppService) it.next();

            if ( appService.getIsCluster() ) {
                Collection services = appService.getServiceCluster().getServices();
                Iterator serviceIterator = services.iterator();
                while ( serviceIterator.hasNext() ) {
                    Service service = (Service)serviceIterator.next();
                    Server server = service.getServer();
                    
                    // Don't bother with entire cluster if type is platform svc
                    if (server.getServerType().getVirtual())
                        break;

                    Integer serverId = ((ServerPK)
                            server.getPrimaryKey()).getId();
                    
                    if (serverCollection.containsKey(serverId))
                        continue;
                    
                    serverCollection.put(serverId, server);
                }
            } else {
                Server server = appService.getService().getServer();
                if (!server.getServerType().getVirtual()) {
                    Integer serverId = server.getId();
                    
                    if (serverCollection.containsKey(serverId))
                        continue;
                    
                    serverCollection.put(serverId, server);
                }
            }
        }
    
        for(Iterator i = serverCollection.entrySet().iterator(); i.hasNext();) {
            Map.Entry entry = (Map.Entry) i.next();
            Server aServer = (Server) entry.getValue();
            
            // first, if they specified a server type, then filter on it
            if(servTypeId != APPDEF_RES_TYPE_UNDEFINED && 
               !(aServer.getServerType().getId().equals(servTypeId)) ) {
                i.remove();
            }
            // otherwise, remove the server if its not viewable
            else if(!authzPks.contains(aServer.getPrimaryKey())) {
                i.remove();
            }
        } 
        
        return serverCollection.values();
    }

    /**
     * Get servers by application.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @param pc The page control for this page list.
     * @return A List of ServerValue objects representing servers that support 
     * the given application that the subject is allowed to view.
     */
    public PageList getServersByApplication(AuthzSubjectValue subject,
                                            Integer appId, PageControl pc)
        throws ServerNotFoundException, ApplicationNotFoundException,
               PermissionException {
        return this.getServersByApplication(subject, appId,
                                            this.APPDEF_RES_TYPE_UNDEFINED, pc);
    }

    /**
     * Get servers by application and serverType.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @param pc The page control for this page list.
     * @return A List of ServerValue objects representing servers that support 
     * the given application that the subject is allowed to view.
     */
    public PageList getServersByApplication(AuthzSubjectValue subject,
                                            Integer appId, Integer servTypeId,
                                            PageControl pc)
        throws ServerNotFoundException, ApplicationNotFoundException,
               PermissionException {
        Collection serverCollection =
            this.getServersByApplicationImpl(subject, appId, servTypeId);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverCollection, pc);
    }

    /**
     * Get server IDs by application and serverType.
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     *
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @param pc The page control for this page list.
     * @return A List of ServerValue objects representing servers that support 
     * the given application that the subject is allowed to view.
     */
    public Integer[] getServerIdsByApplication(AuthzSubjectValue subject,
                                               Integer appId,
                                               Integer servTypeId)
        throws ServerNotFoundException, ApplicationNotFoundException,
               PermissionException {
        Collection servers =
            this.getServersByApplicationImpl(subject, appId, servTypeId);
        
        Integer[] ids = new Integer[servers.size()];
        Iterator it = servers.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Server server = (Server) it.next();
            ids[i] = server.getId();
        }

        return ids;
    }

    /**
     * Private method to validate a new ServerValue object
     * @param ServerValue
     * @throws ValidationException
     */
    private void validateNewServer(ServerValue sv) throws ValidationException {
        String msg = null;
        // first check if its new 
        if(sv.idHasBeenSet()) {
            msg = "This server is not new. It has id: " + sv.getId();
        }
        // else if(someotherthing)  ...

        // Now check if there's a msg set and throw accordingly
        if(msg != null) {
            throw new ValidationException(msg);
        }
    }     

    /**
     * Validate a new Server Type
     * @throws ValidationException
     */  
    public void validateNewServerType(ServerTypeValue stv, List suppPlatTypes) 
        throws ValidationException {
            // Currently no validation rules for new ServerTypes
    } 

    /** 
     * Update a server
     * @param existing 
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ServerValue updateServer(AuthzSubjectValue subject,
                                    ServerValue existing)
        throws PermissionException, UpdateException,
               AppdefDuplicateNameException, ServerNotFoundException {
        try {
            Server server =
                getServerDAO().findByPrimaryKey(existing.getPrimaryKey());
            checkModifyPermission(subject, server.getEntityId());
            existing.setModifiedBy(subject.getName());
            existing.setMTime(new Long(System.currentTimeMillis()));
            trimStrings(existing);
            if(!existing.getName().equals(server.getName())) {
                ResourceValue rv = getAuthzResource(getServerResourceType(),
                    existing.getId());
                rv.setName(existing.getName());
                updateAuthzResource(rv);
            }
            if(server.matchesValueObject(existing)) {
                log.debug("No changes found between value object and entity");
                return existing;
            } else {
                server.updateServer(existing);
                // flush the cache
                VOCache.getInstance().removeServer(existing.getId());
                return getServerById(subject, existing.getId());
            }
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            throw new ServerNotFoundException(existing.getId(), e);
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException(existing.getId(), e);
        }
    }

    /**
     * Change server owner
     * @param who
     * @param serverId
     * @param newOwner
     * @ejb:interface-method
     * @ejb:transaction type="REQUIRESNEW"
     */
    public void changeServerOwner(AuthzSubjectValue who,
                                         Integer serverId,
                                         AuthzSubjectValue newOwner)
        throws PermissionException, ServerNotFoundException {
        ServerPK aPK = new ServerPK(serverId);
        Server serverEJB;
        try {
            // first lookup the server
            serverEJB = getServerDAO().findByPrimaryKey(aPK);
            // check if the caller can modify this server
            checkModifyPermission(who, serverEJB.getEntityId());
            // now get its authz resource
            ResourceValue authzRes = getServerResourceValue(aPK);
            // change the authz owner
            getResourceManager().setResourceOwner(who, authzRes, newOwner);
            // update the owner field in the appdef table -- YUCK
            serverEJB.setOwner(newOwner.getName());
            serverEJB.setModifiedBy(who.getName());
            // flush cache
            VOCache.getInstance().removeServer(serverId);
        } catch (NamingException e) {
            throw new SystemException(e);
        } catch (FinderException e) {
            throw new ServerNotFoundException(serverId, e);
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException(serverId, e);
        }
        
    }


    /**
     * Update server types
     * @param ServerTypeValue
     * @ejb:interface-method
     * @ejb:transaction type="RequiresNew"
     */
    public void updateServerTypes(String plugin, ServerTypeInfo[] infos)
        throws CreateException, FinderException, RemoveException {
        VOCache cache = VOCache.getInstance();

        // First, put all of the infos into a Hash
        HashMap infoMap = new HashMap();
        for (int i = 0; i < infos.length; i++) {
            String name = infos[i].getName();
            ServerTypeInfo sinfo =
                (ServerTypeInfo)infoMap.get(name);

            if (sinfo == null) {
                //first time we've seen this type
                //clone it incase we have to update the platforms
                infoMap.put(name, infos[i].clone());
            }
            else {
                //already seen this type; just update the platforms.
                //this allows server types of the same name to support
                //different families of platforms in the plugins.
                String[] platforms =
                    (String[])ArrayUtil.merge(sinfo.getValidPlatformTypes(),
                                              infos[i].getValidPlatformTypes(),
                                              new String[0]);
                sinfo.setValidPlatformTypes(platforms);
            }
        }

        ServerTypeDAO stLHome = getServerTypeDAO();
        Collection curServers = stLHome.findByPlugin(plugin);
            
        for (Iterator i = curServers.iterator(); i.hasNext();) {
            ServerType stlocal = (ServerType) i.next();
            String serverName = stlocal.getName();
            ServerTypeInfo sinfo =
                (ServerTypeInfo) infoMap.remove(serverName);

            // See if this exists
            if (sinfo == null) {
                log.debug("Removing ServerType: " + serverName);
                // flush cache
                cache.removeServerType(stlocal.getId());
                stLHome.remove(stlocal);
            } else {
                String   curDesc    = stlocal.getDescription();
                Collection      curPlats   = stlocal.getPlatformTypes();
                String   newDesc    = sinfo.getDescription();
                String[] newPlats   = sinfo.getValidPlatformTypes();
                boolean  updatePlats;

                log.debug("Updating ServerType: " + serverName);
                        
                if (!newDesc.equals(curDesc))
                    stlocal.setDescription(newDesc);

                // See if we need to update the supported platforms
                updatePlats = newPlats.length != curPlats.size();
                if(updatePlats == false){
                    // Ensure that the lists are the same
                    for(Iterator k = curPlats.iterator(); k.hasNext(); ){
                        PlatformType pLocal = (PlatformType)k.next();
                        int j;
                            
                        for(j=0; j<newPlats.length; j++){
                            if(newPlats[j].equals(pLocal.getName()))
                                break;
                        }
                        if(j == newPlats.length){
                            updatePlats = true;
                            break;
                        }
                    }
                }

                if(updatePlats == true){
                    Set platSet;

                    try {
                        platSet = getPlatformTypeSet(newPlats);
                    } catch(FinderException exc){
                        throw new CreateException("Could not setup " +
                                                  "server '" + serverName + "' because: " +
                                                  exc.getMessage());
                    }
                    // iterate over the collection to flush the cache
                    for(Iterator it = platSet.iterator(); it.hasNext();) {
                        PlatformType pt = (PlatformType)it.next();
                        cache.removePlatformType(pt.getId());
                    }
                    stlocal.setPlatformTypes(platSet);
                }
            }
        }
            
        // Now create the left-overs
        for (Iterator i = infoMap.values().iterator(); i.hasNext(); ) {
            ServerTypeInfo sinfo = (ServerTypeInfo) i.next();
            ServerType stype = new ServerType();
            Set platSet;

            log.debug("Creating new ServerType: " + sinfo.getName());
            stype.setPlugin(plugin);
            stype.setName(sinfo.getName());
            stype.setDescription(sinfo.getDescription());
            stype.setVirtual(sinfo.isVirtual());
            try {
                String newPlats[] = sinfo.getValidPlatformTypes();
                    
                platSet = getPlatformTypeSet(newPlats);
            } catch(FinderException exc){
                throw new CreateException("Could not setup " +
                                          "server '" + sinfo.getName() + "' because: " +
                                          exc.getMessage());
            }
            stype.setPlatformTypes(platSet);
            // Now create the server type
            stLHome.create(stype);

            // expire the underlying platform types Bug# 9795
            for(Iterator pti = platSet.iterator(); pti.hasNext();) {
                PlatformTypePK ptPk = ((PlatformType)pti.next()).getPrimaryKey();
                cache.removePlatformType(ptPk.getId());
            }
        }
    }

    /**
     * Get a Set of PlatformTypeLocal objects which map to the names
     * as given by the argument.
     */
    private Set getPlatformTypeSet(String[] platNames)
        throws FinderException {
        PlatformTypeDAO platHome =
            DAOFactory.getDAOFactory().getPlatformTypeDAO();
        HashSet res = new HashSet();
            
        for(int i=0; i<platNames.length; i++){
            PlatformType pType;
            
            pType = platHome.findByName(platNames[i]);
            if (pType == null) {
                throw new FinderException("Could not find platform type '" +
                                          platNames[i] + "'");
            }
            res.add(pType);
        }
        return res;
    }

    /**
     * Create the Authz resource and verify that the user has 
     * correct permissions
     * @param serverName TODO
     * @param serverId TODO
     * @param subject - the user creating
     */
    private void createAuthzServer(String serverName, 
                                   Integer serverId,
                                   Integer platformId,
                                   boolean isVirtual, 
                                   AuthzSubjectValue subject)
        throws CreateException, FinderException, PermissionException {
        log.debug("Being Authz CreateServer");
        if(log.isDebugEnabled()) {
            log.debug("Checking for: " + AuthzConstants.platformOpAddServer + 
                " for subject: " + subject);
        }
        checkPermission(subject, getPlatformResourceType(), 
                        platformId, 
                        AuthzConstants.platformOpAddServer);
        createAuthzResource(subject, 
                            getServerResourceType(), 
                            serverId,
                            serverName,
                            isVirtual);
        
    }

    /**
     * Get the AUTHZ ResourceValue for a Server
     * @param ejb - the Server EJB
     * @return ResourceValue
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public ResourceValue getServerResourceValue(ServerPK pk)
        throws FinderException, NamingException {
        return this.getAuthzResource(getServerResourceType(),
            pk.getId());
    }

    /**
     * Trim all string attributes
     */
    private void trimStrings(ServerValue server) {
        if (server.getDescription() != null)
            server.setDescription(server.getDescription().trim());
        if (server.getInstallPath() != null)
            server.setInstallPath(server.getInstallPath().trim());
        if (server.getAutoinventoryIdentifier() != null)
            server.setAutoinventoryIdentifier(server.getAutoinventoryIdentifier().trim());
        if (server.getLocation() != null)
            server.setLocation(server.getLocation().trim());
        if (server.getName() != null)
            server.setName(server.getName().trim());
    }

    /**
     * Create a server manager session bean.
     * @exception CreateException If an error occurs creating the pager
     * for the bean.
     */
    public void ejbCreate() throws CreateException {
        try {
            valuePager = Pager.getPager(VALUE_PROCESSOR);
        } catch ( Exception e ) {
            throw new CreateException("Could not create value pager:" + e);
        }
    }

    public void ejbRemove() { }
    public void ejbActivate() { }
    public void ejbPassivate() { }
}
