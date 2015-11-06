/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.cli.gui;

import static java.security.AccessController.doPrivileged;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServerConnection;
import javax.swing.ImageIcon;
import javax.swing.JInternalFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.jboss.as.cli.CliInitializationException;
import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandContextFactory;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.impl.ExistingChannelModelControllerClient;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.Connection;
import org.jboss.remotingjmx.RemotingMBeanServerConnection;
import org.jboss.threads.JBossThreadFactory;
import org.xnio.IoFuture;
import org.xnio.OptionMap;

import com.sun.tools.jconsole.JConsoleContext;
import com.sun.tools.jconsole.JConsoleContext.ConnectionState;
import com.sun.tools.jconsole.JConsolePlugin;
import java.beans.PropertyChangeEvent;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Stan Silvert ssilvert@redhat.com (C) 2012 Red Hat Inc.
 */
public class JConsoleCLIPlugin extends JConsolePlugin {

    private static final String MSG_CANNOT_ESTABLISH_CONNECTION = "Cannot establish a remote connection to the application server";
    private static final int DEFAULT_MAX_THREADS = 6;
    private static final String LABEL = "JBoss EAP CLI";

    // Global count of created pools
    private static final AtomicInteger executorCount = new AtomicInteger();

    private ConnectDialog dialog;
    CliGuiContext cliGuiCtx;
    private JPanel jconsolePanel;
    private boolean initComplete = false;
    private ModelControllerClient connectedClient;
    private boolean isConnected = false;

    private ComponentListener doConnectListener;
    @Override
    public Map<String,JPanel> getTabs() {
        Map<String, JPanel> panelMap = new HashMap<String, JPanel>();
        this.jconsolePanel = new JPanel(new BorderLayout());
        this.dialog = new ConnectDialog(this, this.jconsolePanel);
        doConnectListener = new ComponentListener() {

            @Override
            public void componentShown(ComponentEvent arg0) {
                try {
                    if(!isConnected)
                        connect();
                } catch (Exception e) {
                    throw new RuntimeException("Error connecting to JBoss AS.", e);
                }
            }

            @Override
            public void componentResized(ComponentEvent arg0) {
            }

            @Override
            public void componentMoved(ComponentEvent arg0) {
            }

            @Override
            public void componentHidden(ComponentEvent arg0) {
                if(dialog.isStarted()){
                    dialog.stop();
                }
            }
        };
        jconsolePanel.addComponentListener(doConnectListener);
        panelMap.put(LABEL, jconsolePanel);
        return panelMap;
    }

    private void connect() throws Exception {
        JConsoleContext jcCtx = this.getContext();
        MBeanServerConnection mbeanServerConn = jcCtx.getMBeanServerConnection();

        if (mbeanServerConn instanceof RemotingMBeanServerConnection) {
            final CommandContext cmdCtx
                    = CommandContextFactory.getInstance().newCommandContext();
            if (connectUsingRemoting(cmdCtx,
                    (RemotingMBeanServerConnection) mbeanServerConn)) {
                // Set a listener for connection state change.
                jcCtx.addPropertyChangeListener((PropertyChangeEvent evt) -> {
                    Logger.getLogger("org.foo").log(Level.FINER,
                            "Received property change {0} value {1}",
                            new Object[]{evt.getPropertyName(), evt.getNewValue()});
                    if (JConsoleContext.CONNECTION_STATE_PROPERTY.equals(evt.getPropertyName())) {
                        ConnectionState state = (ConnectionState) evt.getNewValue();
                        if (state == ConnectionState.CONNECTED) {
                            try {
                                // Rebuild the ModelControllerClient
                                RemotingMBeanServerConnection rmbsc
                                        = (RemotingMBeanServerConnection) getContext().getMBeanServerConnection();
                                connectUsingRemoting(cmdCtx, rmbsc);
                                connectedClient = cmdCtx.getModelControllerClient();
                                isConnected = true;
                            } catch (Exception ex) {
                                Logger.getLogger(JConsoleCLIPlugin.class.getName()).
                                        log(Level.SEVERE, null, ex);
                            }
                        } else {
                            isConnected = false;
                        }
                    }
                });
                connectedClient = cmdCtx.getModelControllerClient();
                Supplier<ModelControllerClient> client = () -> {
                    return connectedClient;
                };
                init(cmdCtx, client);
            } else {
                 JOptionPane.showInternalMessageDialog(jconsolePanel, MSG_CANNOT_ESTABLISH_CONNECTION);
            }
        } else {
            //show dialog
            dialog.start();
        }
    }

    private boolean connectUsingRemoting(CommandContext cmdCtx, RemotingMBeanServerConnection rmtMBeanSvrConn)
            throws IOException, CliInitializationException {
        Connection conn = rmtMBeanSvrConn.getConnection();
        Channel channel;
        final IoFuture<Channel> futureChannel = conn.openChannel("management", OptionMap.EMPTY);
        IoFuture.Status result = futureChannel.await(5, TimeUnit.SECONDS);
        if (result == IoFuture.Status.DONE) {
            channel = futureChannel.get();
        } else {
            futureChannel.cancel();
            return false;
        }

        ModelControllerClient modelCtlrClient = ExistingChannelModelControllerClient.createReceiving(channel, createExecutor());
        cmdCtx.bindClient(modelCtlrClient);

        return true;
    }

    private ExecutorService createExecutor() {
        final ThreadGroup group = new ThreadGroup("management-client-thread");
        final ThreadFactory threadFactory = doPrivileged(new PrivilegedAction<JBossThreadFactory>() {
            public JBossThreadFactory run() {
                return new JBossThreadFactory(group, Boolean.FALSE, null, "%G " + executorCount.incrementAndGet() + "-%t", null, null);
            }
        });
        return new ThreadPoolExecutor(2, DEFAULT_MAX_THREADS, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory);
    }

    @Override
    public SwingWorker<?, ?> newSwingWorker() {
        if (!initComplete && isConnected) {
            initComplete = true;
            configureMyJInternalFrame();
        }

        return null;
    }

    public void init(CommandContext cmdCtx) {
        init(cmdCtx, null);
    }

    /**
     * @param cmdCtx
     */
    private void init(CommandContext cmdCtx, Supplier<ModelControllerClient> client) {
        //TODO: add checks
        cliGuiCtx = GuiMain.startEmbedded(cmdCtx, client);
        final JPanel cliGuiPanel = cliGuiCtx.getMainPanel();
        jconsolePanel.setVisible(false);
        dialog.stop();
        jconsolePanel.add(GuiMain.makeMenuBar(cliGuiCtx), BorderLayout.NORTH);
        jconsolePanel.add(cliGuiPanel, BorderLayout.CENTER);
        jconsolePanel.setVisible(true);
        jconsolePanel.repaint();
        isConnected = true;
    }

    private void configureMyJInternalFrame() {
        ImageIcon icon = new ImageIcon(GuiMain.getJBossIcon());
        Component component = jconsolePanel;

        while (component != null) {
            component = component.getParent();
            if (component instanceof JInternalFrame) {
                JInternalFrame frame = (JInternalFrame)component;
                frame.setFrameIcon(icon);
                return;
            }
        }
    }
}
