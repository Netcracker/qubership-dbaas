package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject.X_REQUEST_ID;


@ApplicationScoped
public class AsyncOperations {

    @ConfigProperty(name = "backup.aggregator.async.thread.pool.size", defaultValue = "10")
    int asyncBackupThreadPoolSize;

    private ThreadPoolExecutor backupExecutor;

    @PostConstruct
    void initPools() {
        backupExecutor = new ThreadPoolExecutor(
                asyncBackupThreadPoolSize,
                asyncBackupThreadPoolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new NamedThreadFactory("backups-"));
    }

    public ThreadPoolExecutor getBackupPool() {
        return backupExecutor;
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

}
