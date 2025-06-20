package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.annotation.TimeMeasure;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClient;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_1;


@Slf4j
public class DbaasAdapterRESTClient extends AbstractDbaasAdapterRESTClient implements DbaasAdapter {

    private DbaasAdapterRestClient restClient;

    public DbaasAdapterRESTClient(String adapterAddress, String type, DbaasAdapterRestClient restClient, String identifier, AdapterActionTrackerClient tracker) {
        super(adapterAddress, type, identifier, tracker, VERSION_1, null);
        this.restClient = restClient;
    }

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "CreateDatabase"}, fieldTags = {"type", "identifier"})
    public CreatedDatabase createDatabase(AbstractDatabaseCreateRequest databaseCreateRequest, String microserviceName) {
        log.info("Adapter {} request to {} of type {} to create db with classifier {}",
                super.identifier(), super.adapterAddress(), super.type(), databaseCreateRequest.getClassifier());
        AdapterDatabaseCreateRequest adapterRequest = new AdapterDatabaseCreateRequest();
        Map<String, Object> map = new HashMap<>();
        map.put(CLASSIFIER, databaseCreateRequest.getClassifier());
        map.put(MICROSERVICE_NAME, microserviceName);
        adapterRequest.setMetadata(map);
        if (databaseCreateRequest instanceof DatabaseCreateRequest) {
            adapterRequest.setDbName(((DatabaseCreateRequest) databaseCreateRequest).getDbName());
            adapterRequest.setPassword(((DatabaseCreateRequest) databaseCreateRequest).getPassword());
            adapterRequest.setUsername(((DatabaseCreateRequest) databaseCreateRequest).getUsername());
            adapterRequest.setInitScriptIdentifiers(((DatabaseCreateRequest) databaseCreateRequest).getInitScriptIdentifiers());
        }
        adapterRequest.setNamePrefix(databaseCreateRequest.getNamePrefix());
        adapterRequest.setSettings(isSettingsSupported() ? databaseCreateRequest.getSettings() : null);
        return restClient.createDatabase(type(), adapterRequest);
    }

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "CreateDatabase"}, fieldTags = {"type", "identifier"})
    public CreatedDatabaseV3 createDatabaseV3(AbstractDatabaseCreateRequest databaseCreateRequest, String microserviceName) {
        throw new UnsupportedOperationException("create database V3 is not supported by V1 adapter API");
    }

    @Override
    public EnsuredUser ensureUser(String username, String password, String dbName, String role) {
        throw new UnsupportedOperationException("Ensure user with role is not supported by V1 adapter API");
    }

    @Override
    public Response.StatusType restorePasswords(Map<String, Object> settings, List<Map<String, Object>> connectionProperties) {
        throw new UnsupportedOperationException("Passwords restoration is not supported by V1 adapter API");
    }

    @Override
    public EnsuredUser createUser(String username, String password, String dbName, String role) {
        throw new UnsupportedOperationException("Get or create user operation is not supported by V1 adapter API");
    }

    @Override
    public boolean deleteUser(List<DbResource> resources) {
        throw new UnsupportedOperationException("Delete user is not supported by V1 adapter API");
    }

    @Override
    protected TrackedAction restoreBackup(String backupId, RestoreRequest restoreRequest) {
        throw new UnsupportedOperationException("New restore backup is not supported by V1 adapter API");
    }

    @Override
    protected TrackedAction restoreBackupOld(String localId, boolean regenerateNames, List<String> databases) {
        return restClient.restoreBackup(type(), localId, regenerateNames, databases);
    }

    @Override
    protected TrackedAction collectBackup(Boolean allowEviction, String keep, List<String> databases) {
        return restClient.collectBackup(type(), allowEviction, keep, databases);
    }

    @Override
    public TrackedAction trackBackup(String action, String trackId) {
        return restClient.trackBackup(type(), action, trackId);
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
        restClient.dropDatabase(type(), resources);
    }

    @Override
    protected EnsuredUser ensureUser(String username, UserEnsureRequest request) {
        return username == null ? restClient.ensureUser(type(), request) : restClient.ensureUser(type(), username, request);
    }

    @Override
    public void changeMetaData(String dbName, Map<String, Object> metadata) {
        log.info("Call adapter {} of type {} to update {} metadata {}",
                adapterAddress(), type(), dbName, metadata);
        restClient.changeMetaData(type(), dbName, metadata);
    }

    @Override
    public Map<String, DescribedDatabase> describeDatabases(Collection<String> databases) {
        log.info("Call adapter {} of type {} to describe {} databases",
                adapterAddress(), type(), databases.size());
        return restClient.describeDatabases(type(), databases);
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
