package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;


import com.netcracker.cloud.dbaas.dto.*;
import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import com.netcracker.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import com.netcracker.cloud.dbaas.dto.v3.GetOrCreateUserAdapterRequest;
import com.netcracker.cloud.dbaas.dto.v3.UserEnsureRequestV3;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.BackupAdapterRequest;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalBackupAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.LogicalRestoreAdapterResponse;
import com.netcracker.cloud.dbaas.entity.dto.backupV2.RestoreAdapterRequest;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.backup.TrackedAction;
import com.netcracker.cloud.dbaas.monitoring.AdapterHealthStatus;
import com.netcracker.cloud.dbaas.monitoring.annotation.TimeMeasure;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import jakarta.ws.rs.core.Response;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;


@EqualsAndHashCode(callSuper = true)
@Slf4j
public class DbaasAdapterRESTClientV2 extends AbstractDbaasAdapterRESTClient implements DbaasAdapter {

    private DbaasAdapterRestClientV2 restClient;

    public DbaasAdapterRESTClientV2(String adapterAddress, String type, DbaasAdapterRestClientV2 restClient, String identifier, AdapterActionTrackerClient tracker) {
        this(adapterAddress, type, restClient, identifier, tracker, null);
    }

    public DbaasAdapterRESTClientV2(String adapterAddress, String type, DbaasAdapterRestClientV2 restClient, String identifier, AdapterActionTrackerClient tracker, ApiVersion apiVersions) {
        super(adapterAddress, type, identifier, tracker, VERSION_2, apiVersions);
        this.restClient = restClient;
    }

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "CreateDatabase"}, fieldTags = {"type", "identifier"})
    public CreatedDatabase createDatabase(AbstractDatabaseCreateRequest databaseCreateRequest, String microserviceName) {
        throw new UnsupportedOperationException("create database V1 is not supported by V2 adapter API");
    }

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "CreateDatabase"}, fieldTags = {"type", "identifier"})
    public CreatedDatabaseV3 createDatabaseV3(AbstractDatabaseCreateRequest databaseCreateRequest, String microserviceName) {
        StructuredLog.info(log, "Adapter request to of type to create db with classifier", "arg0", identifier(), "adapter", adapterAddress(), "type", type(), "classifier", databaseCreateRequest.getClassifier());
        AdapterDatabaseCreateRequest adapterRequest = new AdapterDatabaseCreateRequest();
        Map<String, Object> metadata = buildMetadata(databaseCreateRequest.getClassifier());
        adapterRequest.setMetadata(metadata);
        metadata.put(MICROSERVICE_NAME, microserviceName);
        adapterRequest.setNamePrefix(databaseCreateRequest.getNamePrefix());
        if (databaseCreateRequest instanceof DatabaseCreateRequest) {
            adapterRequest.setDbName(((DatabaseCreateRequest) databaseCreateRequest).getDbName());
            adapterRequest.setPassword(((DatabaseCreateRequest) databaseCreateRequest).getPassword());
            adapterRequest.setUsername(((DatabaseCreateRequest) databaseCreateRequest).getUsername());
            adapterRequest.setInitScriptIdentifiers(((DatabaseCreateRequest) databaseCreateRequest).getInitScriptIdentifiers());
        }
        adapterRequest.setSettings(isSettingsSupported() ? databaseCreateRequest.getSettings() : null);
        return restClient.createDatabase(type(), adapterRequest);
    }

    @Override
    public EnsuredUser ensureUser(String username, String password, String dbName, String role) {
        StructuredLog.info(log, "Call adapter of type to ensure user of db", "adapter", adapterAddress(), "type", type(), "username", username, "dbName", dbName);
        UserEnsureRequestV3 request = new UserEnsureRequestV3(dbName, password, role);
        return username == null ? restClient.ensureUser(type(), request) : restClient.ensureUser(type(), username, request);
    }

    @Override
    public Response.StatusType restorePasswords(Map<String, Object> settings, List<Map<String, Object>> connectionProperties) {
        RestorePasswordsAdapterRequest request = new RestorePasswordsAdapterRequest(settings, connectionProperties);
        Response response = restClient.restorePassword(type(), request);
        StructuredLog.info(log, "Received status code= and body= from adapter with id= and type= on passwords restoration request", "status", response.getStatus(), "arg1", response.getEntity(), "arg2", identifier(), "type", type());
        return response.getStatusInfo();
    }

    public EnsuredUser createUser(String dbName, String password, String role, String usernamePrefix) {
        StructuredLog.info(log, "Call adapter of type to get or create user of db", "adapter", adapterAddress(), "type", type(), "dbName", dbName);
        GetOrCreateUserAdapterRequest request = new GetOrCreateUserAdapterRequest(dbName, role, usernamePrefix);
        return restClient.createUser(type(), request);
    }

    public boolean deleteUser(List<DbResource> resources) {
        StructuredLog.info(log, "Call adapter of type to delete user", "adapter", adapterAddress(), "type", type());
        try (Response response = restClient.dropResources(type(), resources)) {
            return Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily());
        }
    }

    @Override
    protected TrackedAction restoreBackup(String backupId, RestoreRequest restoreRequest) {
        return restClient.restoreBackup(type(), backupId, restoreRequest);
    }

    @Override
    protected TrackedAction restoreBackupOld(String backupId, boolean regenerateNames, List<String> databases) {
        return restClient.restoreBackup(type(), backupId, regenerateNames, databases);
    }

    @Override
    protected TrackedAction collectBackup(Boolean allowEviction, String keep, List<String> databases) {
        return restClient.collectBackup(type(), allowEviction, keep, databases);
    }

    @Override
    public LogicalBackupAdapterResponse backupV2(BackupAdapterRequest backupAdapterRequest) {
        return restClient.backupV2(type(), backupAdapterRequest);
    }

    @Override
    public LogicalRestoreAdapterResponse restoreV2(String logicalBackupName, boolean dryRun, RestoreAdapterRequest restoreAdapterRequest) {
        return restClient.restoreV2(type(),logicalBackupName, dryRun, restoreAdapterRequest);
    }

    @Override
    public LogicalRestoreAdapterResponse trackRestoreV2(String logicalRestoreName, String storageName, String blobPath) {
        return restClient.trackRestoreV2(type(), logicalRestoreName, storageName, blobPath);
    }

    @Override
    public TrackedAction trackBackup(String action, String trackId) {
        return restClient.trackBackup(type(), action, trackId);
    }

    @Override
    public LogicalBackupAdapterResponse trackBackupV2(String logicalBackupName, String storageName, String blobPath) {
        return restClient.trackBackupV2(type(), logicalBackupName, storageName, blobPath);
    }

    @Override
    public void deleteBackupV2(String logicalBackupName, String blobPath) {
        restClient.deleteBackupV2(type(), logicalBackupName, blobPath);
    }


    @Override
    protected String deleteBackup(String localId) {
        return restClient.deleteBackup(type(), localId);
    }

    @Override
    protected AdapterHealthStatus getHealth() {
        return restClient.getHealth();
    }

    @Override
    protected void dropDatabase(List<DbResource> resources) {
        restClient.dropResources(type(), resources).close();
    }

    @Override
    protected EnsuredUser ensureUser(String username, UserEnsureRequest request) {
        return restClient.ensureUser(type(), username, request);
    }

    @Override
    public void changeMetaData(String dbName, Map<String, Object> metadata) {
        StructuredLog.info(log, "Call adapter of type to update metadata", "adapter", adapterAddress(), "type", type(), "dbName", dbName, "metadata", metadata);
        restClient.changeMetaData(type(), dbName, metadata);
    }

    @Override
    public Map<String, DescribedDatabase> describeDatabases(Collection<String> databases) {
        StructuredLog.info(log, "Call adapter of type to describe databases", "adapter", adapterAddress(), "type", type(), "arg2", databases.size());
        return restClient.describeDatabases(type(), true, true, databases);
    }

    @Override
    protected String updateSettings(String dbName, UpdateSettingsAdapterRequest request) {
        return restClient.updateSettings(type(), dbName, request);
    }

    @Override
    protected Set<String> doGetDatabases() {
        return restClient.getDatabases(type());
    }

    @Override
    protected Map<String, Boolean> sendSupportsRequest() {
        return restClient.supports(type());
    }
}
