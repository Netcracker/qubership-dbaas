package com.netcracker.cloud.dbaas.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


@Slf4j
@ApplicationScoped
public class LockService {
    private static final Duration DEFAULT_LOCK_AT_MOST = Duration.of(10, TimeUnit.MINUTES.toChronoUnit());
    private final LockProvider keepAliveLockProvider;
    private final LockProvider expirableLockProvider;

    @Inject
    public LockService(KeepAliveLockProvider keepAliveLockProvider, ExtensibleLockProvider extensibleLockProvider) {
        this.keepAliveLockProvider = keepAliveLockProvider;
        this.expirableLockProvider = extensibleLockProvider;
    }

    public boolean isLocked(String lockName) {
        Optional<SimpleLock> simpleLock = keepAliveLockProvider.lock(getLockConfiguration(lockName));

        if (simpleLock.isEmpty())
            return true;

        unlock(simpleLock.get());
        return false;
    }

    public Optional<SimpleLock> getKeepAliveLock(LockConfiguration configuration) {
        return keepAliveLockProvider.lock(configuration);
    }

    public Optional<SimpleLock> getExpirableLock(LockConfiguration configuration) {
        return expirableLockProvider.lock(configuration);
    }

    public static void unlock(SimpleLock simpleLock) {
        try {
            simpleLock.unlock();
        } catch (IllegalStateException ex) {
            log.debug("LockService is already unlocked", ex);
        }
    }

    public static LockConfiguration getLockConfiguration(String name) {
        return getLockConfiguration(name, DEFAULT_LOCK_AT_MOST, Duration.ZERO);
    }

    public static LockConfiguration getLockConfiguration(String name, Duration lockAtMost) {
        return getLockConfiguration(name, lockAtMost, lockAtMost);
    }

    public static LockConfiguration getLockConfiguration(String name, Duration lockAtMost, Duration lockAtLeast) {
        return new LockConfiguration(
                Instant.now(),
                name,
                lockAtMost,
                lockAtLeast
        );
    }
}
