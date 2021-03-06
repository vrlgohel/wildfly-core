/*
 * Copyright (C) 2014 Red Hat, inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package org.jboss.as.server.deployment;

import static java.lang.Long.getLong;
import static java.lang.System.getSecurityManager;
import static java.security.AccessController.doPrivileged;

import java.security.PrivilegedAction;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.as.controller.ControlledProcessStateService;
import org.jboss.as.controller.ModelControllerClientFactory;
import org.jboss.as.server.Services;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * Service in charge with cleaning left over contents from the content repository.
 * @author <a href="mailto:ehugonne@redhat.com">Emmanuel Hugonnet</a> (c) 2014 Red Hat, inc.
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class ContentCleanerService implements Service {

   /**
     * For testing purpose only.
     *
     * @deprecated DON'T USE IT.
     */
    @Deprecated
    private static final String UNSUPPORTED_PROPERTY = "org.wildfly.unsupported.content.repository.obsolescence";
    /**
     * The conten repository cleaner will test content for clean-up every 5 minutes.
     */
    public static final long DEFAULT_INTERVAL = getSecurityManager() == null ? getLong(UNSUPPORTED_PROPERTY, 300000L) : doPrivileged((PrivilegedAction<Long>) () -> getLong(UNSUPPORTED_PROPERTY, 300000L));
    /**
     * Standard ServiceName under which a service controller for an instance of
     * @code Service<ContentRepository> would be registered.
     */
    private static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("content-repository-cleaner");

    private final Supplier<ModelControllerClientFactory> clientFactorySupplier;
    private final Supplier<ScheduledExecutorService> scheduledExecutorSupplier;
    private final Supplier<ControlledProcessStateService> controlledProcessStateServiceSupplier;
    private final Supplier<ExecutorService> executorServiceSupplier;

    private ContentRepositoryCleaner deploymentContentCleaner;
    private final long interval;
    private final boolean server;
    private final TimeUnit unit;

    public static void addService(final ServiceTarget serviceTarget, final ServiceName clientFactoryService, final ServiceName scheduledExecutorServiceName) {
        final ServiceBuilder<?> builder = serviceTarget.addService(SERVICE_NAME);
        final Supplier<ModelControllerClientFactory> mccfSupplier = builder.requires(clientFactoryService);
        final Supplier<ControlledProcessStateService> cpssSupplier = builder.requires(ControlledProcessStateService.SERVICE_NAME);
        final Supplier<ScheduledExecutorService> sesSupplier = builder.requires(scheduledExecutorServiceName);
        final Supplier<ExecutorService> esSupplier = Services.requireServerExecutor(builder);
        builder.setInstance(new ContentCleanerService(true, mccfSupplier, cpssSupplier, sesSupplier, esSupplier));
        builder.install();
    }

    public static void addServiceOnHostController(final ServiceTarget serviceTarget, final ServiceName hostControllerServiceName, final ServiceName clientFactoryServiceName,
                                                  final ServiceName hostControllerExecutorServiceName, final ServiceName scheduledExecutorServiceName) {
        final ServiceBuilder<?> builder = serviceTarget.addService(SERVICE_NAME);
        final Supplier<ModelControllerClientFactory> mccfSupplier = builder.requires(clientFactoryServiceName);
        final Supplier<ControlledProcessStateService> cpssSupplier = builder.requires(ControlledProcessStateService.SERVICE_NAME);
        final Supplier<ScheduledExecutorService> sesSupplier = builder.requires(scheduledExecutorServiceName);
        final Supplier<ExecutorService> esSupplier = builder.requires(hostControllerExecutorServiceName);
        builder.setInstance(new ContentCleanerService(false, mccfSupplier, cpssSupplier, sesSupplier, esSupplier));
        builder.install();
    }

    private ContentCleanerService(final boolean server,
                                  final Supplier<ModelControllerClientFactory> clientFactorySupplier,
                                  final Supplier<ControlledProcessStateService> controlledProcessStateServiceSupplier,
                                  final Supplier<ScheduledExecutorService> scheduledExecutorSupplier,
                                  final Supplier<ExecutorService> executorServiceSupplier) {
        this.interval = DEFAULT_INTERVAL;
        this.unit = TimeUnit.MILLISECONDS;
        this.server = server;
        this.clientFactorySupplier = clientFactorySupplier;
        this.controlledProcessStateServiceSupplier = controlledProcessStateServiceSupplier;
        this.scheduledExecutorSupplier = scheduledExecutorSupplier;
        this.executorServiceSupplier = executorServiceSupplier;
    }

    @Override
    public synchronized void start(StartContext context) throws StartException {
        this.deploymentContentCleaner = new ContentRepositoryCleaner(
                clientFactorySupplier.get().createSuperUserClient(executorServiceSupplier.get(), false),
                controlledProcessStateServiceSupplier.get(),
                scheduledExecutorSupplier.get(), unit.toMillis(interval), server);
        deploymentContentCleaner.startScan();
    }

    @Override
    public synchronized void stop(StopContext context) {
        final ContentRepositoryCleaner contentCleaner = this.deploymentContentCleaner;
        this.deploymentContentCleaner = null;
        contentCleaner.stopScan();
    }

}
