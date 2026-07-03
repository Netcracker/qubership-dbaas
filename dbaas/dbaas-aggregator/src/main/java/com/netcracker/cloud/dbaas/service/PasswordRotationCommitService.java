package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;

/**
 * Carries the transaction boundary for a single database's password rotation.
 * <p>
 * It is intentionally a separate bean from {@link PasswordRotationService}: a {@code @Transactional}
 * method invoked from another method of the same bean bypasses the CDI proxy and the interceptor never
 * fires. Being a distinct collaborator, {@link #commitRotation(DatabaseRegistry)} is always called through
 * the proxy, so the annotation takes effect. Since rotation no longer runs inside an outer transaction,
 * the default {@code REQUIRED} propagation opens a fresh transaction per database — each successful
 * rotation commits independently and survives a sibling's failure later in the batch.
 */
@Slf4j
@ApplicationScoped
public class PasswordRotationCommitService {

    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;

    @Transactional
    public void commitRotation(DatabaseRegistry databaseRegistry) {
        databaseRegistry.getDatabase().setLastRotatedAt(OffsetDateTime.now());
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .saveInternalDatabase(databaseRegistry);
    }
}
