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

package org.hyperic.hq.ui.action.resource.common.monitor.alerts.config;

import java.util.HashSet;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.hyperic.hq.bizapp.shared.EventsBoss;
import org.hyperic.hq.bizapp.shared.action.EmailActionConfig;
import org.hyperic.hq.events.shared.ActionValue;
import org.hyperic.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An Action that removes emails for an alert definition.
 * 
 */
public class RemoveOthersAction
    extends RemoveNotificationsAction {
    private final Log log = LogFactory.getLog(RemoveOthersAction.class.getName());

    @Autowired
    public RemoveOthersAction(EventsBoss eventsBoss) {
        super(eventsBoss);
    }

    public int getNotificationType() {
        return EmailActionConfig.TYPE_EMAILS;
    }

    /**
     * Handles the actual work of removing emails from the action.
     */
    protected ActionForward handleRemove(ActionMapping mapping, HttpServletRequest request, Map<String, Object> params,
                                         Integer sessionID, ActionValue action, EmailActionConfig ea, EventsBoss eb,
                                         RemoveNotificationsForm rnForm) throws Exception {
        String[] emails = rnForm.getEmails();
        if (null != emails) {
            log.debug("emails.length=" + emails.length);
            HashSet<Object> storedEmails = new HashSet<Object>();
            storedEmails.addAll(ea.getUsers());
            log.debug("storedEmails (pre): " + storedEmails);
            for (int i = 0; i < emails.length; ++i) {
                storedEmails.remove(emails[i]);
            }
            log.debug("storedEmails (post): " + storedEmails);
            ea.setNames(StringUtil.iteratorToString(storedEmails.iterator(), ","));
            action.setConfig(ea.getConfigResponse().encode());
            eb.updateAction(sessionID.intValue(), action);
        }

        return returnSuccess(request, mapping, params);
    }
}
