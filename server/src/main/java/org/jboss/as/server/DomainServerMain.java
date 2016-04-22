/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import org.jboss.as.network.NetworkUtils;

import org.jboss.as.process.ExitCodes;
import org.jboss.as.process.ProcessController;
import org.jboss.as.process.protocol.StreamUtils;
import org.jboss.as.process.stdin.Base64InputStream;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.as.server.mgmt.domain.HostControllerClient;
import org.jboss.as.server.mgmt.domain.HostControllerConnectionService;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.handlers.ConsoleHandler;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.service.ServiceActivator;
import org.jboss.msc.service.ServiceActivatorContext;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.stdio.LoggingOutputStream;
import org.jboss.stdio.NullInputStream;
import org.jboss.stdio.SimpleStdioContextSelector;
import org.jboss.stdio.StdioContext;
import org.jboss.threads.AsyncFuture;

/**
 * The main entry point for domain-managed server instances.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class DomainServerMain {

    private DomainServerMain() {
    }

    /**
     * Main entry point.  Reads and executes the command object from standard input.
     *
     * @param args ignored
     */
    public static void main(String[] args) {

        final InputStream initialInput = new Base64InputStream(System.in);
        final PrintStream initialError = System.err;

        // Make sure our original stdio is properly captured.
        try {
            Class.forName(ConsoleHandler.class.getName(), true, ConsoleHandler.class.getClassLoader());
        } catch (Throwable ignored) {
        }

        // Install JBoss Stdio to avoid any nasty crosstalk.
        StdioContext.install();
        final StdioContext context = StdioContext.create(
            new NullInputStream(),
            new LoggingOutputStream(org.jboss.logmanager.Logger.getLogger("stdout"), Level.INFO),
            new LoggingOutputStream(org.jboss.logmanager.Logger.getLogger("stderr"), Level.ERROR)
        );
        StdioContext.setStdioContextSelector(new SimpleStdioContextSelector(context));

        final byte[] asAuthBytes = new byte[ProcessController.AUTH_BYTES_ENCODED_LENGTH];
        try {
            StreamUtils.readFully(initialInput, asAuthBytes);
        } catch (IOException e) {
            e.printStackTrace();
            SystemExiter.abort(ExitCodes.FAILED);
            throw new IllegalStateException(); // not reached
        }

        final MarshallerFactory factory = Marshalling.getMarshallerFactory("river", DomainServerMain.class.getClassLoader());
        final Unmarshaller unmarshaller;
        final ByteInput byteInput;
        final AsyncFuture<ServiceContainer> containerFuture;
        try {
            Module.registerURLStreamHandlerFactoryModule(Module.getBootModuleLoader().loadModule(ModuleIdentifier.create("org.jboss.vfs")));
            final MarshallingConfiguration configuration = new MarshallingConfiguration();
            configuration.setVersion(2);
            configuration.setClassResolver(new SimpleClassResolver(DomainServerMain.class.getClassLoader()));
            unmarshaller = factory.createUnmarshaller(configuration);
            byteInput = Marshalling.createByteInput(initialInput);
            unmarshaller.start(byteInput);
            final ServerTask task = unmarshaller.readObject(ServerTask.class);
            unmarshaller.finish();
            containerFuture = task.run(Arrays.<ServiceActivator>asList(new ServiceActivator() {
                @Override
                public void activate(final ServiceActivatorContext serviceActivatorContext) {
                    // TODO activate host controller client service
                }
            }));
        } catch (Exception e) {
            e.printStackTrace(initialError);
            SystemExiter.abort(ExitCodes.FAILED);
            throw new IllegalStateException(); // not reached
        } finally {
        }
        for (;;) {
            try {
                final String scheme = StreamUtils.readUTFZBytes(initialInput);
//                String scheme = "remote";
                final String hostName = StreamUtils.readUTFZBytes(initialInput);
                final int port = StreamUtils.readInt(initialInput);
                final boolean managementSubsystemEndpoint = StreamUtils.readBoolean(initialInput);
                final byte[] authBytes = new byte[ProcessController.AUTH_BYTES_ENCODED_LENGTH];
                StreamUtils.readFully(initialInput, authBytes);
                final String authKey = new String(authBytes, Charset.forName("US-ASCII"));
                URI hostControllerUri = new URI(scheme, null, NetworkUtils.formatPossibleIpv6Address(hostName), port, null, null, null);
                // Get the host-controller server client
                final ServiceContainer container = containerFuture.get();
                if (!container.isShutdown()) {
                    // otherwise, ServiceNotFoundException or IllegalStateException is thrown because HostControllerClient is stopped
                    final HostControllerClient client = getRequiredService(container,
                            HostControllerConnectionService.SERVICE_NAME, HostControllerClient.class);
                    // Reconnect to the host-controller
                    client.reconnect(hostControllerUri, authKey, managementSubsystemEndpoint);
                }

            } catch (InterruptedIOException e) {
                Thread.interrupted();
                // ignore
            } catch (EOFException e) {
                // this means it's time to exit
                break;
            } catch (Exception e) {
                e.printStackTrace();
                break;
            }
        }
        //we may be attempting a graceful shutdown, in which case we need to wait
        final ServiceContainer container;
        try {
            container = containerFuture.get();
            ServiceController<?> controller = container.getService(GracefulShutdownService.SERVICE_NAME);
            if(controller != null) {
                ((GracefulShutdownService)controller.getValue()).awaitSuspend();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Once the input stream is cut off, shut down
        SystemExiter.logAndExit(ServerLogger.ROOT_LOGGER::shuttingDownInResponseToProcessControllerSignal, ExitCodes.NORMAL);
        throw new IllegalStateException(); // not reached
    }

    static <T> T getRequiredService(final ServiceContainer container, final ServiceName serviceName, Class<T> type) {
        final ServiceController<?> controller = container.getRequiredService(serviceName);
        return type.cast(controller.getValue());
    }

}
