/*
 * Generated by XDoclet - Do not edit!
 */
package org.hyperic.hq.appdef.shared;

import org.hyperic.hq.appdef.galerts.ResourceAuxLog;
import org.hyperic.hq.appdef.server.session.ResourceAuxLogPojo;
import org.hyperic.hq.galerts.server.session.GalertAuxLog;
import org.hyperic.hq.galerts.server.session.GalertDef;

/**
 * Local interface for ResourceAuxLogManager.
 */
public interface ResourceAuxLogManager {

   public ResourceAuxLogPojo create( GalertAuxLog log,ResourceAuxLog logInfo ) ;

   public void remove( GalertAuxLog log ) ;

   public ResourceAuxLogPojo find( GalertAuxLog log ) ;

   public void removeAll( GalertDef def ) ;

}
