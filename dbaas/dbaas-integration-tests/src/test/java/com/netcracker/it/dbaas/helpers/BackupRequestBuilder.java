package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.backup.v1.BackupRequest;
import com.netcracker.it.dbaas.entity.backup.v1.ExternalDatabaseStrategy;
import com.netcracker.it.dbaas.entity.backup.v1.FilterCriteria;

import java.util.UUID;
import java.util.function.Consumer;

import static com.netcracker.it.dbaas.helpers.BackupHelperV1.BLOB_PATH;
import static com.netcracker.it.dbaas.helpers.BackupHelperV1.STORAGE_NAME;

public class BackupRequestBuilder {
    private final BackupRequest backupRequest;

    public BackupRequestBuilder() {
        this.backupRequest = new BackupRequest();
        this.backupRequest.setBackupName(String.format("dbaas-autotests-%s", UUID.randomUUID()));
        this.backupRequest.setStorageName(STORAGE_NAME);
        this.backupRequest.setBlobPath(BLOB_PATH);
        this.backupRequest.setExternalDatabaseStrategy(ExternalDatabaseStrategy.FAIL);
        this.backupRequest.setIgnoreNotBackupableDatabases(false);
    }

    public BackupRequestBuilder(BackupRequest backupRequest) {
        this.backupRequest = backupRequest;
    }

    public BackupRequestBuilder name(String name) {
        this.backupRequest.setBackupName(name);
        return this;
    }

    public BackupRequestBuilder storage(String storageName) {
        this.backupRequest.setStorageName(storageName);
        return this;
    }

    public BackupRequestBuilder blobPath(String blobPath) {
        this.backupRequest.setBlobPath(blobPath);
        return this;
    }

    public BackupRequestBuilder filterCriteria(FilterCriteria filterCriteria) {
        this.backupRequest.setFilterCriteria(filterCriteria);
        return this;
    }

    public BackupRequestBuilder externalDatabaseStrategy(ExternalDatabaseStrategy strategy) {
        this.backupRequest.setExternalDatabaseStrategy(strategy);
        return this;
    }

    public BackupRequestBuilder ignoreNotBackupableDb(boolean ignoreNotBackupableDatabases) {
        this.backupRequest.setIgnoreNotBackupableDatabases(ignoreNotBackupableDatabases);
        return this;
    }

    public BackupRequestBuilder filterCriteria(Consumer<FilterCriteriaBuilder> fcc) {
        var filterCriteria = new FilterCriteriaBuilder();
        fcc.accept(filterCriteria);
        this.backupRequest.setFilterCriteria(filterCriteria.build());
        return this;
    }

    public BackupRequest build() {
        return this.backupRequest;
    }
}
