package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import org.qubership.cloud.dbaas.dto.CreatedDatabase;
import org.qubership.cloud.dbaas.dto.DescribedDatabase;
import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.backup.DeleteResult;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.exceptions.InteruptedPollingException;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DbaasAdapter {
    String identifier();

    String adapterAddress();

    RestoreResult restore(String targetNamespace, DatabasesBackup backup, boolean regenerateNames, List<DatabaseRegistry> databases, Map<String, String> prefixMap) throws InteruptedPollingException;

    DatabasesBackup backup(List<String> databases, Boolean allowEviction) throws InteruptedPollingException;

    DeleteResult delete(DatabasesBackup backup);

    boolean validate(DatabasesBackup backup);

    TrackedAction trackBackup(String action, String trackId);

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
