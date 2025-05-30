package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.RestoreRequest;
import org.qubership.cloud.dbaas.dto.UpdateSettingsAdapterRequest;
import org.qubership.cloud.dbaas.dto.backup.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.*;
import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.UserEnsureRequest;
import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import org.qubership.cloud.dbaas.exceptions.DBBackupValidationException;
import org.qubership.cloud.dbaas.exceptions.InteruptedPollingException;
import org.qubership.cloud.dbaas.exceptions.MultiValidationException;
import org.qubership.cloud.dbaas.exceptions.ValidationException;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.annotation.TimeMeasure;
import org.qubership.cloud.dbaas.utils.DbaasBackupUtils;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.map.LinkedMap;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

import javax.annotation.Nullable;

@Slf4j
public abstract class AbstractDbaasAdapterRESTClient implements DbaasAdapter {

    public static final String TYPE = "type";
    public static final String CLASSIFIER = "classifier";
    public static final String MICROSERVICE_NAME = "microserviceName";
    public static final String METRIC_NAME = "dbaas.adapter.database.operation";
    private static final String DEFAULT_MS_NAME = "unknown";
    @NonNull
    private String adapterAddress;
    @NonNull
    private String type;
    @NonNull
    private String identifier;
    @NonNull
    private AdapterActionTrackerClient tracker;
    private Boolean disabled = false;
    @NonNull
    private String supportedVersion;
    private AdapterSupports supports;

    protected AbstractDbaasAdapterRESTClient(@NonNull String adapterAddress, @NonNull String type, @NonNull String identifier, @NonNull AdapterActionTrackerClient tracker, @NonNull String supportedVersion, @Nullable ApiVersion apiVersions) {
        this.adapterAddress = adapterAddress;
        this.type = type;
        this.identifier = identifier;
        this.tracker = tracker;
        this.supportedVersion = supportedVersion;
        this.supports = new AdapterSupports(this, supportedVersion, apiVersions);
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public String adapterAddress() {
        return adapterAddress;
    }

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "RestoreBackup"}, fieldTags = {"type", "identifier"})
    public RestoreResult restore(String targetNamespace, DatabasesBackup backup, boolean regenerateNames, List<DatabaseRegistry> databases, Map<String, String> prefixMap) {
        log.info("Start to restore backup of type {} in adapter {} on address {} : {}", type, identifier, adapterAddress, backup);
        List<ValidationException> errors = new ArrayList<>();
        if (!identifier.equals(backup.getAdapterId())) {
            errors.add(new DBBackupValidationException(Source.builder().pointer("/adapter_id").build(),
                    String.format("Incorrect adapter identifier: '%s'", backup.getAdapterId())));
        }
        if (StringUtils.isBlank(backup.getLocalId())) {
            errors.add(new DBBackupValidationException(Source.builder().pointer("/local_id").build(),
                    "Backup identifier cannot be empty"));
        }
        if (!errors.isEmpty()) {
            throw new MultiValidationException(errors);
        }
        TrackedAction restoreActionTrack;
        if (supports.contract(2, 1)) {
            restoreActionTrack = sendNewRestoreRequest(targetNamespace, backup, regenerateNames, databases, prefixMap);
        } else {
            restoreActionTrack = sendOldRestoreRequest(backup, regenerateNames);
        }
        log.info("Received action track from adapter {}, {} : {}", identifier, adapterAddress, restoreActionTrack);
        try {
            return tracker.waitForRestore(backup, restoreActionTrack, this);
        } catch (InteruptedPollingException e) {
            log.warn("Restore was interrupted");
            Thread.currentThread().interrupt();
            RestoreResult result = new RestoreResult(this.identifier());
            result.setStatus(Status.FAIL);
            return result;
        }

    }

    private TrackedAction sendNewRestoreRequest(String namespace, DatabasesBackup backup, boolean regenerateNames, List<DatabaseRegistry> databases, Map<String, String> prefixMap) {
        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setRegenerateNames(regenerateNames);

        backup.getDatabases().forEach(dbName -> {
            Optional<DatabaseRegistry> registry = databases.stream()
                .filter(r -> dbName.equals(DbaasBackupUtils.getDatabaseName(r)))
                .findFirst();

            String microserviceName = registry.isPresent()
                ? (String) registry.get().getClassifier().get(MICROSERVICE_NAME)
                : DEFAULT_MS_NAME;

            String namePrefix = prefixMap.get(dbName);

            restoreRequest.getDatabases().add(new RestoreRequest.Database(
                namespace, microserviceName, dbName, namePrefix
            ));
        });
        if (regenerateNames) {
            log.info("Request restoration regenerating database names");
        }
        return restoreBackup(backup.getLocalId(), restoreRequest);
    }

    protected abstract TrackedAction restoreBackup(String backupId, RestoreRequest restoreRequest);

    private TrackedAction sendOldRestoreRequest(DatabasesBackup backup, boolean regenerateNames) {
        String urlAdapterRestore = "{adapterAddress}/api/" + supportedVersion + "/dbaas/adapter/{type}/backups/{backupId}/restore";
        if (regenerateNames) {
            log.info("Request restoration regenerating database names");
        }
        return restoreBackupOld(backup.getLocalId(), regenerateNames, backup.getDatabases());
    }

    protected abstract TrackedAction restoreBackupOld(String localId, boolean regenerateNames, List<String> databases);

    @Override
    public boolean validate(DatabasesBackup backup) {
        try {
            TrackedAction action = new TrackedAction();
            action.setAction(TrackedAction.Action.BACKUP);
            action.setTrackId(backup.getTrackId());
            action.setTrackPath(backup.getTrackPath());
            DatabasesBackup validatedBackup = tracker.validateBackup(action, this);
            return Status.SUCCESS == validatedBackup.getStatus();
        } catch (Exception e) {
            log.error("Failed to validate local databases backup {} , track id {}", backup.getLocalId(), backup.getTrackId());
            return false;
        }
    }

    protected abstract TrackedAction collectBackup(Boolean allowEviction, String keep, List<String> databases);

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "BackupDatabase"}, fieldTags = {"type", "identifier"})
    public DatabasesBackup backup(List<String> databases, Boolean allowEviction) throws InteruptedPollingException {
        log.info("Adapter {} request to collect {} backup from {} with allowEviction {}", identifier, type, adapterAddress, allowEviction);
        LinkedMap<String, List<String>> params = new LinkedMap<>();
        params.put("allowEviction", Collections.singletonList(allowEviction.toString()));

        TrackedAction backupActionTrack = collectBackup(allowEviction, null, databases);
        DatabasesBackup backup = null;
        try {
            backup = tracker.waitForBackup(backupActionTrack, this);
        } catch (InteruptedPollingException e) {
            log.warn("Backup was interrupted");
            throw e;
        }
        if (backup == null) {
            DatabasesBackup fail = new DatabasesBackup();
            fail.setStatus(Status.FAIL);
            return fail;
        }
        backup.setAdapterId(identifier);
        if (backup.getStatus() != Status.FAIL) {
            backup.setStatus(Status.SUCCESS);
        }
        if (backup.getDatabases() == null) {
            backup.setDatabases(databases);
        }
        return backup;
    }

    protected abstract String deleteBackup(String localId);

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "DeleteBackup"}, fieldTags = {"type", "identifier"})
    public DeleteResult delete(DatabasesBackup backupToDelete) {
        log.info("Start to delete backup of type {} in adapter {} on address {} : {}", type, identifier, adapterAddress, backupToDelete);
        DeleteResult deleteResponse = new DeleteResult();
        deleteResponse.setDatabasesBackup(backupToDelete);
        deleteResponse.setAdapterId(identifier);
        try {
            if (log.isDebugEnabled()) {
                log.debug("Sending request to {}/api/{}/dbaas/adapter/{}/backups/{}", adapterAddress, supportedVersion, type, backupToDelete.getLocalId());
            }
            String response = deleteBackup(backupToDelete.getLocalId());
            deleteResponse.setStatus(Status.SUCCESS);
            deleteResponse.setMessage(response);
            log.info("Received response from adapter {}, {} : {}", identifier, adapterAddress, deleteResponse);
        } catch (WebApplicationException e) {
            log.error("Received response from adapter {}, {} : {}", identifier, adapterAddress, e.getMessage());
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                deleteResponse.setStatus(Status.SUCCESS);
                deleteResponse.setMessage("Endpoint to delete backup not implemented on adapter yet!");
            } else {
                deleteResponse.setStatus(Status.FAIL);
                deleteResponse.setMessage("Adapter returned " + e.getResponse().getStatus() + " with error " + e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Created DeleteResponse from request {}/api/{}/dbaas/adapter/{}/backups/{} is {}", adapterAddress, supportedVersion, type, backupToDelete.getLocalId(), deleteResponse);
        }
        return deleteResponse;
    }

    @Override
    public String type() {
        return type;
    }

    protected abstract AdapterHealthStatus getHealth();

    @Override
    public AdapterHealthStatus getAdapterHealth() {
        AdapterHealthStatus status = null;
        try {
            status = getHealth();
        } catch (Exception e) {
            log.error("Failed to get health of adapter of type {}", type, e);
            return new AdapterHealthStatus(AdapterHealthStatus.HEALTH_CHECK_STATUS_PROBLEM);
        }
        return status;
    }

    @Override
    public Boolean isDisabled() {
        return disabled;
    }

    protected abstract void dropDatabase(List<DbResource> resources);

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "DeleteDatabase"}, fieldTags = {"type", "identifier"})
    public void dropDatabase(DatabaseRegistry databaseRegistry) {
        if (CollectionUtils.isEmpty(databaseRegistry.getResources())) {
            log.error("Database with classifier {} have empty resources {}", databaseRegistry.getClassifier(), databaseRegistry.getResources());
            return;
        }
        log.info("Call adapter {} of type {} to drop db with classifier {} and resources {}",
                adapterAddress, type, databaseRegistry.getClassifier(), databaseRegistry.getResources());
        dropDatabase(databaseRegistry.getResources());
    }

    @Override
    @TimeMeasure(value = METRIC_NAME, tags = {"operation", "DeleteDatabase"}, fieldTags = {"type", "identifier"})
    public void dropDatabase(Database database) {
        if (CollectionUtils.isEmpty(database.getResources())) {
            log.error("Database have empty resources {}", database.getResources());
            return;
        }
        log.info("Call adapter {} of type {} to drop db and resources {}",
                adapterAddress, type, database.getResources());
        dropDatabase(database.getResources());
    }

    protected abstract EnsuredUser ensureUser(String username, UserEnsureRequest request);

    @Override
    public EnsuredUser ensureUser(String username, String password, String dbName) {
        log.info("Call adapter {} of type {} to ensure user {} of db {}",
                adapterAddress, type, username, dbName);
        UserEnsureRequest request = new UserEnsureRequest(dbName, password);
        return ensureUser(username, request);
    }

    protected abstract String updateSettings(String dbName, UpdateSettingsAdapterRequest request);

    @Override
    public String updateSettings(String dbName, Map<String, Object> currentSettings, Map<String, Object> newSettings) {
        log.info("Call adapter {} of type {} to update db '{}' from current settings: {} to new settings: {}",
                adapterAddress, type, dbName, currentSettings, newSettings);
        UpdateSettingsAdapterRequest request = new UpdateSettingsAdapterRequest();
        request.setCurrentSettings(currentSettings);
        request.setNewSettings(newSettings);
        try {
            String response = updateSettings(dbName, request);
            return (response != null ? response : "");
        } catch (WebApplicationException e) {
            // failed to update setting on adapter side
            log.error("Failed to update settings for db {} from settings: {} to new settings: {}, errorStatus: {} {}, errorMessage: {}",
                    dbName, currentSettings, newSettings, e.getResponse().getStatus(), e.getResponse().getStatusInfo().getReasonPhrase(), e.getResponse().getEntity(), e);
            throw e;
        } catch (Exception e) {
            log.error("Problem with access to adapter {} ", this, e);
            throw e;
        }
    }

    protected abstract Set<String> doGetDatabases();

    @Override
    public Set<String> getDatabases() {
        log.info("Call adapter {} of type {} to get databases", adapterAddress, type);
        try {
            return doGetDatabases();
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.METHOD_NOT_ALLOWED.getStatusCode()) {
                throw new NotAllowedException(Response.status(Response.Status.METHOD_NOT_ALLOWED.getStatusCode(), e.getResponse().getStatusInfo().getReasonPhrase().concat(String.format(
                        ". DbaaS adapter with address %s does not have 'GET /api/" + supportedVersion + "/dbaas/adapter/%s/databases' API (getDatabases). You need to contact cloud administrator and update this adapter to a newer version.",
                        adapterAddress,
                        type)))
                        .entity(e.getResponse().getEntity()).build());
            } else {
                throw e;
            }
        }
    }

    protected Boolean isSettingsSupported() {
        return supports.settings();
    }

    @Override
    public boolean isUsersSupported() {
        return supports.users();
    }

    @Override
    public String getSupportedVersion() {
        return supportedVersion;
    }

    @Override
    public boolean isBackupRestoreSupported() {
        return supports.backupRestore();
    }

    @Override
    public boolean isDescribeDatabasesSupported() {
        return supports.describeDatabases();
    }

    @Override
    public Map<String, Boolean> supports() {
        return supports.supportedFeatures();
    }

    public static Map<String, Object> buildMetadata(Map<String, Object> classifier) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(CLASSIFIER, classifier);
        metadata.put(MICROSERVICE_NAME, classifier.get(MICROSERVICE_NAME));
        return metadata;
    }

    protected abstract Map<String, Boolean> sendSupportsRequest();
}
