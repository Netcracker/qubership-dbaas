package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;

@Slf4j
@ApplicationScoped
public class AsyncOperations {
    @Getter
    private final ExecutorService backupExecutor;
    @Getter
    private final ExecutorService debugExecutor;
    @Getter
    private final ExecutorService cleanupExecutor;

    @Inject
    public AsyncOperations(
            @ConfigProperty(name = "backup.aggregator.async.thread.pool.size", defaultValue = "10") int backupPoolSize
    ) {
        backupExecutor = new ThreadPoolExecutor(
                backupPoolSize, backupPoolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("backups-")
        );
        debugExecutor = Executors.newVirtualThreadPerTaskExecutor();
        cleanupExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("cleanup-"));
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final ThreadFactory defaultWrapped = Executors.defaultThreadFactory();
        private final String namePrefix;

        NamedThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        public Thread newThread(@NonNull Runnable run) {
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
        shutdown("debugExecutor", debugExecutor);
        shutdown("cleanupExecutor", cleanupExecutor);
    }

    private void shutdown(String serviceName, ExecutorService executorService) {
        log.info("Shutting down '{}' executor", serviceName);
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.info("'{}' executor is still not terminated", serviceName);

                executorService.shutdownNow();

                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("'{}' executor was not terminated even after await", serviceName);
                }
            }
        } catch (InterruptedException ex) {
            log.error("Error happened during shutting down '{}' executor: ", serviceName, ex);

            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Finish shutting down '{}' executor", serviceName);
    }
}
