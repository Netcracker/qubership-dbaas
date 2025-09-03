package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.qubership.cloud.dbaas.entity.pg.backupV2.LogicalRestore;

import java.util.UUID;

@Transactional
@ApplicationScoped
public class LogicalRestoreRepository implements PanacheRepositoryBase<LogicalRestore, UUID> {
}
