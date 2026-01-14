package com.netcracker.it.dbaas.entity.backup.v3;

import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class NamespaceBackupV3 {
    private UUID id;
    private List<DatabasesBackup> backups;
    private List<String> failReasons;
    private List<DatabaseV3> databases;
    private String namespace;

    public enum Status {
        FAIL, ACTIVE, PROCEEDING, RESTORING, INVALIDATED, DELETION_FAILED
    }

    private Status status;

    public boolean canRestore() {
        return Status.ACTIVE.equals(status);
    }

    public boolean canBeDeleted() {
        return !Status.FAIL.equals(status)
                && !Status.RESTORING.equals(status)
                && !Status.PROCEEDING.equals(status);
    }

    private List<DatabaseResponse> databasesCreatedForTest;
}
