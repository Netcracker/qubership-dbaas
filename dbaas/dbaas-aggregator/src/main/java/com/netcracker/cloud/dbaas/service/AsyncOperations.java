package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@ApplicationScoped
public class AsyncOperations {

    private final ThreadPoolExecutor backupExecutor;
    private final ExecutorService debugExecutorService;

    @Inject
    public AsyncOperations(
            @ConfigProperty(
                    name = "backup.aggregator.async.thread.pool.size",
                    defaultValue = "10"
            ) int poolSize
    ) {
        this.backupExecutor = new ThreadPoolExecutor(
                poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("backups-")
        );
        this.debugExecutorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public ThreadPoolExecutor getBackupPool() {
        return backupExecutor;
    }

    public ExecutorService getDebugExecutor() {
        return debugExecutorService;
    }

    class NamedThreadFactory implements ThreadFactory {
        private ThreadFactory defaultWrapped = Executors.defaultThreadFactory();
        private final String namePrefix;

        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        public Thread newThread(Runnable run) {
            Thread thread = defaultWrapped.newThread(run);
            thread.setName(namePrefix + thread.getName());
            return thread;
        }
    }

    public <T> Supplier<T> wrapWithContext(Supplier<T> task) {
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();
        return () -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
            return task.get();
        };
    }

    public Runnable wrapWithContext(Runnable task) {
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();
        return () -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
            task.run();
        };
    }

    public <T, U> BiConsumer<T, U> wrapWithContext(BiConsumer<T, U> action) {
        var requestId = ((XRequestIdContextObject) ContextManager.get(X_REQUEST_ID)).getRequestId();
        return (t, u) -> {
            ContextManager.set(X_REQUEST_ID, new XRequestIdContextObject(requestId));
            action.accept(t, u);
        };
    }

    @PreDestroy
    void cleanUp() {
        shutdown("backupExecutor", backupExecutor);
        shutdown("debugExecutorService", debugExecutorService);
    }

    private void shutdown(String serviceName,ExecutorService executorService) {
        log.info("Start shutting down '{}' service", serviceName);
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.info("'{}' service is still not terminated", serviceName);

                executorService.shutdownNow();

                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("'{}' service was not terminated even after await", serviceName);
                }
            }
        } catch (InterruptedException ex) {
            log.error("Error happened during shutting down '{}' service: ", serviceName, ex);

            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Finish shutting down '{}' service", serviceName);
    }
}
