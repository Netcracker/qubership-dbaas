package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupDatabase;
import com.netcracker.cloud.dbaas.entity.pg.BackupExternalDatabase;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
<<<<<<<< HEAD:dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/repositories/pg/jpa/BackupExternalDatabaseRepository.java
import org.qubership.cloud.dbaas.entity.pg.backupV2.BackupExternalDatabase;
========
>>>>>>>> e4eccd2 (refactor: rename packages):dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/repositories/pg/jpa/BackupDatabaseRepository.java

import java.util.UUID;

@Transactional
@ApplicationScoped
public class BackupExternalDatabaseRepository implements PanacheRepositoryBase<BackupExternalDatabase, UUID> {

}
