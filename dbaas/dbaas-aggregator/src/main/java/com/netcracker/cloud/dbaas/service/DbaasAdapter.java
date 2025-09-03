package com.netcracker.cloud.dbaas.service;

import jakarta.ws.rs.core.Response;
import com.netcracker.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import com.netcracker.cloud.dbaas.dto.CreatedDatabase;
import com.netcracker.cloud.dbaas.dto.DescribedDatabase;
import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.backup.DeleteResult;
import com.netcracker.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import com.netcracker.cloud.dbaas.entity.pg.backup.RestoreResult;
import com.netcracker.cloud.dbaas.entity.pg.backup.TrackedAction;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.LogicalBackupStatus;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.LogicalRestoreStatus;
import com.netcracker.cloud.dbaas.exceptions.InteruptedPollingException;
import com.netcracker.cloud.dbaas.monitoring.AdapterHealthStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DbaasAdapter {
    String identifier();

    String adapterAddress();

    RestoreResult restore(String targetNamespace, DatabasesBackup backup, boolean regenerateNames, List<DatabaseRegistry> databases, Map<String, String> prefixMap) throws InteruptedPollingException;

    DatabasesBackup backup(List<String> databases, Boolean allowEviction) throws InteruptedPollingException;

    String backupV2(List<String> dbNames);

    String restoreV2(String backupName, boolean dryRun, String storageName, String blobPath, List<Map<String, String>> databases);

    LogicalRestoreStatus trackRestoreV2(String logicalRestoreName);

    DeleteResult delete(DatabasesBackup backup);

    boolean validate(DatabasesBackup backup);

    TrackedAction trackBackup(String action, String trackId);

    LogicalBackupStatus trackBackupV2(String logicalBackupName);

    String type();

    AdapterHealthStatus getAdapterHealth();

    CreatedDatabase createDatabase(AbstractDatabaseCreateRequest databaseCreateRequest, String microserviceName);

    CreatedDatabaseV3 createDatabaseV3(AbstractDatabaseCreateRequest databaseCreateRequest, String microserviceName);

    void dropDatabase(DatabaseRegistry databaseRegistry);

    void dropDatabase(Database database);

    EnsuredUser ensureUser(String username, String password, String dbName);

    EnsuredUser ensureUser(String username, String password, String dbName, String role);

    EnsuredUser createUser(String username, String password, String dbName, String role);

    boolean deleteUser(List<DbResource> resources);

    Set<String> getDatabases();

    void changeMetaData(String database, Map<String, Object> metadata);

    Map<String, DescribedDatabase> describeDatabases(Collection<String> databases);

    String updateSettings(String database, Map<String, Object> currentSettings, Map<String, Object> newSettings);

    Boolean isDisabled();

    boolean isUsersSupported();

    String getSupportedVersion();

    boolean isBackupRestoreSupported();

    boolean isDescribeDatabasesSupported();

    Map<String, Boolean> supports();

    Response.StatusType restorePasswords(Map<String, Object> settings, List<Map<String, Object>> connectionProperties);
}
