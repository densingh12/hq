/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2009-2010], VMware, Inc.
 * This file is part of HQ.
 *
 * HQ is free software; you can redistribute it and/or modify
 *  it under the terms version 2 of the GNU General Public License as
 *  published by the Free Software Foundation. This program is distributed
 *  in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 *  even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 *  USA.
 */

package org.hyperic.hq.agent.server;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.hyperic.hq.agent.AgentConfig;
import org.hyperic.hq.agent.AgentConfigException;
import org.hyperic.hq.agent.AgentMonitorValue;
import org.hyperic.hq.agent.AgentUpgradeManager;
import org.hyperic.hq.agent.bizapp.callback.PlugininventoryCallbackClient;
import org.hyperic.hq.agent.bizapp.callback.StorageProviderFetcher;
import org.hyperic.hq.agent.server.monitor.AgentMonitorInterface;
import org.hyperic.hq.agent.server.monitor.AgentMonitorSimple;
import org.hyperic.hq.product.GenericPlugin;
import org.hyperic.hq.product.PluginInfo;
import org.hyperic.util.security.SecurityUtil;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class AgentManager extends AgentMonitorSimple {

    private final Log logger = LogFactory.getLog(this.getClass().getName());

    private final Map<String, List<AgentNotificationHandler>> notifyHandlers = new ConcurrentHashMap<String, List<AgentNotificationHandler>>();

    private final Map<CharSequence, AgentMonitorInterface> monitorClients = new ConcurrentHashMap<CharSequence, AgentMonitorInterface>();


    protected void startHandlers(AgentService agentService, List<AgentServerHandler> serverHandlers, List<AgentServerHandler> startedHandlers) throws AgentStartException {
        for (AgentServerHandler serverHandler : serverHandlers) {
            try {
                serverHandler.startup(agentService);
                startedHandlers.add(serverHandler);

            } catch (AgentStartException e) {
                logger.error("Error starting plugin " + serverHandler, e);
                throw e;
            } catch (Exception e) {
                logger.error("Unknown exception", e);
                throw new AgentStartException("Error starting plugin " + serverHandler, e);
            }
        }
    }

    public void doInitialCleanup(Properties bootProps) {
        String tmpDir = bootProps.getProperty(AgentConfig.PROP_TMPDIR[0]);

        if (tmpDir != null) {
            try {
                /* update plugins residing in tmp directory prior to cleaning it up */
                List updatedPlugins = AgentUpgradeManager.updatePlugins(bootProps);

                if (!updatedPlugins.isEmpty()) logger.info("Successfully updated plugins: " + updatedPlugins);
            }
            catch (IOException e) {
                logger.error("Failed to update plugins", e);
            }
            //this should always be the case.
            cleanTmpDir(tmpDir);
            System.setProperty("java.io.tmpdir", tmpDir);
        }
    }

    /* TODO remove */

    protected void setProxy(AgentConfig config) {
        if (config.isProxyServerSet()) {
            System.setProperty(AgentConfig.PROP_LATHER_PROXYHOST, config.getProxyIp());
            System.setProperty(AgentConfig.PROP_LATHER_PROXYPORT, String.valueOf(config.getProxyPort()));
        }
    }

    /**
     * Register an object to be called when a notifiation of the specified
     * message class occurs;
     * @param handler  Handler to call to process the notification message
     * @param msgClass Message class to register with
     */
    public void registerNotifyHandler(AgentNotificationHandler handler, String msgClass) {
        List<AgentNotificationHandler> handlers = notifyHandlers.get(msgClass);

        if (handlers == null) {
            handlers = new ArrayList<AgentNotificationHandler>();
            notifyHandlers.put(msgClass, handlers);
        }
        handlers.add(handler);
    }

    /**
     * Send a notification event to all notification handlers which
     * have registered with the specified message class.
     * @param msgClass Message class that the message belongs to
     * @param message  Message to send
     */
    public void sendNotification(String msgClass, String message) {
        if (notifyHandlers.get(msgClass) == null) return;

        logger.debug("Notifying that agent startup failed");

        List<AgentNotificationHandler> handlers = notifyHandlers.get(msgClass);
        for (AgentNotificationHandler objHandler : handlers) {
            objHandler.handleNotification(msgClass, message);
        }
    }

    /**
     * Creates the AgentStorageProvider and sets the hostname. This will be stored in the storage provider
     * and checked on each agent invocation.  This determines if this agent installation has been copied
     * from another machine without removing the data directory.
     * @param config the agent config
     */
    public AgentStorageProvider createStorageProvider(AgentConfig config) throws AgentConfigException {
        AgentStorageProvider storageProvider;

        try {
            Class storageClass = Class.forName(config.getStorageProvider());

            storageProvider = (AgentStorageProvider) storageClass.newInstance();
            storageProvider.init(config.getStorageProviderInfo());

            String currentHost = GenericPlugin.getPlatformName();

            String storedHost = storageProvider.getValue(getAgentHostName());
            if (storedHost == null || storedHost.length() == 0) {
                storageProvider.setValue(getAgentHostName(), currentHost);
            } else {
                if (!storedHost.equals(currentHost)) {
                    throw new AgentConfigException(new StringBuilder("Invalid hostname '").append(currentHost)
                            .append("'. This agent has been configured for '").append(storedHost)
                            .append("'. If this agent has been copied from a different machine please remove ")
                            .append("the data directory and restart the agent").toString());
                }
            }

        } catch (IllegalAccessException exc) {
            throw new AgentConfigException("Unable to access storage provider " + config.getStorageProvider() + ": " + exc.getMessage());
        } catch (InstantiationException exc) {
            throw new AgentConfigException("Unable to instantiate storage provider " + config.getStorageProvider() + ": " + exc.getMessage());
        } catch (AgentStorageException exc) {
            throw new AgentConfigException("Storage provider unable to initialize: " + exc.getMessage());
        } catch (ClassNotFoundException exc) {
            throw new AgentConfigException("Storage provider not found: " + exc.getMessage());
        }

        return storageProvider;
    }

    /**
     * Make sure the storage provider has a certificate DN. If not, create one.
     * @param storageProvider the storage provider to user
     */
    public void addProviderCertificate(AgentStorageProvider storageProvider) throws AgentConfigException {
        String certDN = storageProvider.getValue(getCertDn());

        if (certDN == null || certDN.length() == 0) {
            certDN = generateCertDN();

            storageProvider.setValue(getCertDn(), certDN);

            try {
                storageProvider.flush();
            } catch (AgentStorageException ase) {
                throw new AgentConfigException("Error storing certdn in agent storage: " + ase);
            }
        }
    }

    /**
     * Generates a new certificate DN.
     * @return The new DN that was generated.
     */
    protected String generateCertDN() {
        return "CAM-AGENT-" + SecurityUtil.generateRandomToken();
    }

    protected void registerMonitor(String monitorName, AgentMonitorInterface monitor) {
        monitorClients.put(monitorName, monitor);
    }

    protected AgentMonitorValue[] getMonitorValues(String monitorName, String[] monitorKeys) {
        AgentMonitorInterface iface = monitorClients.get(monitorName);

        if (iface == null) {
            AgentMonitorValue badVal = new AgentMonitorValue();
            badVal.setErrCode(AgentMonitorValue.ERR_BADMONITOR);

            AgentMonitorValue[] res = new AgentMonitorValue[monitorKeys.length];
            for (int i = 0; i < res.length; i++) {
                res[i] = badVal;
            }
            return res;
        }

        return iface.getMonitorValues(monitorKeys);
    }

    /**
     * Server may be down or Provider may not be setup.
     * Either way we want to retry until the data is sent
     * @param plugins         the collection of plugins
     * @param storageProvider the storage provider
     */
    protected void sendPluginStatusToServer(final Collection<PluginInfo> plugins, final AgentStorageProvider storageProvider) {
        new Thread("PluginStatusSender") {
            public void run() {
                while (true) {
                    try {
                        if (storageProvider == null) {
                            logger.debug("trying to send plugin status to the server but " +
                                    "provider has not been setup, will sleep 5 seconds and retry");
                            TimeUnit.MILLISECONDS.sleep(5000);
                            continue;
                        }
                        PlugininventoryCallbackClient client =
                                new PlugininventoryCallbackClient(new StorageProviderFetcher(storageProvider), plugins);
                        logger.info("Sending plugin status to server");

                        client.sendPluginReportToServer();
                        logger.info("Successfully sent plugin status to server");
                        break;
                    } catch (Exception e) {
                        logger.warn("could not send plugin status to server, will retry:  " + e);
                        logger.debug(e, e);
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(5000);
                    } catch (InterruptedException e) {
                        logger.debug(e, e);
                    }
                }
            }
        }.start();
    }

    /**
     * These files should have already been deleted on normal jvm shutdown.
     * However, agent.exe start + CTRL-c on windows leaves them behind.  cleanout at startup.
     * @param tmp the name of the temporary directory
     */
    protected void cleanTmpDir(String tmp) {
        File dir = new File(tmp);
        if (!dir.exists()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            try {
                file.delete();
            } catch (SecurityException e) {
            }
        }
    }

    protected PrintStream newLogStream(String stream, Properties props) {
        Logger logger = Logger.getLogger(stream);
        String logLevel = props.getProperty("agent.logLevel." + stream);
        if (logLevel == null) {
            return null;
        }
        Level level = Level.toLevel(logLevel);
        return new PrintStream(new LoggingOutputStream(logger, level), true);
    }

    /**
     * Redirect System.{out,err} to log4j appender
     * @param props boot props
     */
    protected void redirectStreams(Properties props) {
        PrintStream outStream = newLogStream("SystemOut", props);
        PrintStream errorStream = newLogStream("SystemErr", props);

        if (outStream != null) System.setOut(outStream);

        if (errorStream != null) System.setErr(errorStream); 
    }

    protected void tryForceAgentFailure(AgentConfig bootConfig) throws AgentStartException {
        String rollbackBundle = bootConfig.getBootProperties().getProperty(AgentConfig.PROP_ROLLBACK_AGENT_BUNDLE_UPGRADE[0]);

        if (getCurrentAgentBundle(bootConfig).equals(rollbackBundle)) {
            throw new AgentStartException(AgentConfig.PROP_ROLLBACK_AGENT_BUNDLE_UPGRADE[0] +
                    " property set to force rollback of agent bundle upgrade: " + rollbackBundle);
        }
    }

    /**
     * @return The current agent bundle name.
     */
    public String getCurrentAgentBundle(AgentConfig bootConfig) {
        String agentBundleHome = bootConfig.getBootProperties().getProperty(AgentConfig.PROP_BUNDLEHOME[0]);
        return new File(agentBundleHome).getName();
    }

    public String getCertDn() {
        return "agent.certDN";
    }

    public String getAgentHostName() {
        return "agent.hostName";
    }
}