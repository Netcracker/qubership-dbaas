package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
public class NamespaceBackupDTO {
    private UUID id;

    private List<DatabasesBackup> backups;

    private List<String> failReasons = new ArrayList<>();

    private String namespace;

    private List<Database> databases;
    private List<DatabaseRegistry> databaseRegistries;

    private List<NamespaceRestoration> restorations;

    private Date created = new Date();

    public enum Status {
        FAIL, ACTIVE, PROCEEDING, RESTORING, INVALIDATED, DELETION_FAILED
    }

    private NamespaceBackup.Status status = NamespaceBackup.Status.PROCEEDING;

}
