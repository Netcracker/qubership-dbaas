package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserAdapterRequest;
import org.qubership.cloud.dbaas.dto.v3.UserEnsureRequestV3;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.annotation.TimeMeasure;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import jakarta.ws.rs.core.Response;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_2;


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
        log.info("Adapter {} request to {} of type {} to create db with classifier {}",
                identifier(), adapterAddress(), type(), databaseCreateRequest.getClassifier());
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
        log.info("Call adapter {} of type {} to ensure user {} of db {}",
                adapterAddress(), type(), username, dbName);
        UserEnsureRequestV3 request = new UserEnsureRequestV3(dbName, password, role);
        return username == null ? restClient.ensureUser(type(), request) : restClient.ensureUser(type(), username, request);
    }

    @Override
    public Response.StatusType restorePasswords(Map<String, Object> settings, List<Map<String, Object>> connectionProperties) {
        RestorePasswordsAdapterRequest request = new RestorePasswordsAdapterRequest(settings, connectionProperties);
        Response response = restClient.restorePassword(type(), request);
        log.info("Received status code={} and body={} from adapter with id={} and type={} on passwords restoration request",
                response.getStatus(), response.getEntity(), identifier(), type());
        return response.getStatusInfo();
    }

    public EnsuredUser createUser(String dbName, String password, String role, String usernamePrefix) {
        log.info("Call adapter {} of type {} to get or create user of db {}",
                adapterAddress(), type(), dbName);
        GetOrCreateUserAdapterRequest request = new GetOrCreateUserAdapterRequest(dbName, role, usernamePrefix);
        return restClient.createUser(type(), request);
    }

    public boolean deleteUser(List<DbResource> resources) {
        log.info("Call adapter {} of type {} to delete user",
                adapterAddress(), type());
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
        restClient.dropResources(type(), resources).close();
    }

    @Override
    protected EnsuredUser ensureUser(String username, UserEnsureRequest request) {
        return restClient.ensureUser(type(), username, request);
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
