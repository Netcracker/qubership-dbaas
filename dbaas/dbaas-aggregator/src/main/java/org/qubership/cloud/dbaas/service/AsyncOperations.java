package org.qubership.cloud.dbaas.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.*;


@ApplicationScoped
public class AsyncOperations {

    @ConfigProperty(name = "backup.aggregator.async.thread.pool.size", defaultValue = "10")
    int asyncBackupThreadPoolSize;

    ThreadPoolExecutor backupExecutor;

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
}
