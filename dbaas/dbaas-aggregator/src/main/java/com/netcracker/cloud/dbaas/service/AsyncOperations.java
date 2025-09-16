package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.framework.contexts.xrequestid.XRequestIdContextObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.*;
import java.util.function.Supplier;


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

    public ExecutorService getBackupPool() {
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
        var requestIdObj = (XRequestIdContextObject) ContextManager.get("X_REQUEST_ID");
        return () -> {
            ContextManager.set("X_REQUEST_ID", requestIdObj);
            return task.get();
        };
    }
}
