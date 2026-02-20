package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.dto.*;
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
import com.netcracker.cloud.dbaas.security.filters.AuthFilterSetter;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class SecureDbaasAdapterRestClientV2 implements DbaasAdapterRestClientV2 {
    private final boolean isJwtEnabled;

    private final BasicAuthFilter basicAuthFilter;
    private final KubernetesTokenAuthFilter kubernetesTokenAuthFilter;

    private final DbaasAdapterRestClientV2 restClient;
    private final AuthFilterSetter authFilterSetter;

    private final AtomicReference<Instant> lastTokenAuthSetTime;

    public SecureDbaasAdapterRestClientV2(DbaasAdapterRestClientV2 restClient, BasicAuthFilter basicAuthFilter, KubernetesTokenAuthFilter kubernetesTokenAuthFilter, AuthFilterSetter authFilterSetter, boolean isJwtEnabled) {
        this.restClient = restClient;
        this.basicAuthFilter = basicAuthFilter;
        this.kubernetesTokenAuthFilter = kubernetesTokenAuthFilter;
        this.authFilterSetter = authFilterSetter;
        this.lastTokenAuthSetTime = new AtomicReference<>(Instant.now());
        this.isJwtEnabled = isJwtEnabled;
    }

    private <R> R executeRequest(final Supplier<R> supplier) {
        try {
            if (isJwtEnabled && authFilterSetter.getAuthFilter() instanceof BasicAuthFilter && Duration.between(lastTokenAuthSetTime.get(), Instant.now()).toMinutes() >= 60) {
                authFilterSetter.setAuthFilter(kubernetesTokenAuthFilter);
                lastTokenAuthSetTime.set(Instant.now());
            }
            return supplier.get();
        } catch (WebApplicationException e) {
            if (isJwtEnabled && e.getResponse().getStatus() == Response.Status.UNAUTHORIZED.getStatusCode() && authFilterSetter.getAuthFilter() instanceof KubernetesTokenAuthFilter) {
                authFilterSetter.setAuthFilter(basicAuthFilter);
                return supplier.get();
            }
            throw e;
        }
    }

    @Override
    public AdapterHealthStatus getHealth() {
        return executeRequest(restClient::getHealth);
    }

    @Override
    public Response handshake(String type) {
        return executeRequest(() -> restClient.handshake(type));
    }

    @Override
    public Map<String, Boolean> supports(String type) {
        return executeRequest(() -> restClient.supports(type));
    }

    @Override
    public TrackedAction restoreBackup(String type, String backupId, RestoreRequest restoreRequest) {
        return executeRequest(() -> restClient.restoreBackup(type, backupId, restoreRequest));
    }

    @Override
    public TrackedAction restoreBackup(String type, String backupId, boolean regenerateNames, List<String> databases) {
        return executeRequest(() -> restClient.restoreBackup(type, backupId, regenerateNames, databases));
    }

    @Override
    public TrackedAction collectBackup(String type, Boolean allowEviction, String keep, List<String> databases) {
        return executeRequest(() -> restClient.collectBackup(type, allowEviction, keep, databases));
    }

    @Override
    public TrackedAction trackBackup(String type, String action, String track) {
        return executeRequest(() -> restClient.trackBackup(type, action, track));
    }

    @Override
    public String deleteBackup(String type, String backupId) {
        return executeRequest(() -> restClient.deleteBackup(type, backupId));
    }

    @Override
    public Response dropResources(String type, List<DbResource> resources) {
        return executeRequest(() -> restClient.dropResources(type, resources));
    }

    @Override
    public EnsuredUser ensureUser(String type, String username, UserEnsureRequest request) {
        return executeRequest(() -> restClient.ensureUser(type, username, request));
    }

    @Override
    public EnsuredUser ensureUser(String type, String username, UserEnsureRequestV3 request) {
        return executeRequest(() -> restClient.ensureUser(type, username, request));
    }

    @Override
    public EnsuredUser ensureUser(String type, UserEnsureRequestV3 request) {
        return executeRequest(() -> restClient.ensureUser(type, request));
    }

    @Override
    public EnsuredUser createUser(String type, GetOrCreateUserAdapterRequest request) {
        return executeRequest(() -> restClient.createUser(type, request));
    }

    @Override
    public Response restorePassword(String type, RestorePasswordsAdapterRequest request) {
        return executeRequest(() -> restClient.restorePassword(type, request));
    }

    @Override
    public void changeMetaData(String type, String dbName, Map<String, Object> metadata) {
        executeRequest(() -> {
            restClient.changeMetaData(type, dbName, metadata);
            return null;
        });
    }

    @Override
    public Map<String, DescribedDatabase> describeDatabases(String type, boolean connectionProperties, boolean resources, Collection<String> databases) {
        return executeRequest(() -> restClient.describeDatabases(type, connectionProperties, resources, databases));
    }

    @Override
    public Set<String> getDatabases(String type) {
        return executeRequest(() -> restClient.getDatabases(type));
    }

    @Override
    public CreatedDatabaseV3 createDatabase(String type, AdapterDatabaseCreateRequest createRequest) {
        return executeRequest(() -> restClient.createDatabase(type, createRequest));
    }

    @Override
    public String updateSettings(String type, String dbName, UpdateSettingsAdapterRequest request) {
        return executeRequest(() -> restClient.updateSettings(type, dbName, request));
    }

    @Override
    public LogicalBackupAdapterResponse backupV2(String dbType, BackupAdapterRequest request) {
        return executeRequest(() -> restClient.backupV2(dbType, request));
    }

    @Override
    public LogicalBackupAdapterResponse trackBackupV2(String dbType, String logicalBackupName, String storageName, String blobPath) {
        return executeRequest(() -> restClient.trackBackupV2(dbType, logicalBackupName, storageName, blobPath));
    }

    @Override
    public void deleteBackupV2(String dbType, String logicalBackupName, String blobPath) {
        executeRequest(() -> {
            restClient.deleteBackupV2(dbType, logicalBackupName, blobPath);
            return null;
        });
    }

    @Override
    public LogicalRestoreAdapterResponse restoreV2(String dbType, String logicalRestoreName, Boolean dryRun, RestoreAdapterRequest request) {
        return executeRequest(() -> restClient.restoreV2(dbType, logicalRestoreName, dryRun, request));
    }

    @Override
    public LogicalRestoreAdapterResponse trackRestoreV2(String dbType, String logicalBackupName, String storageName, String blobPath) {
        return executeRequest(() -> restClient.trackRestoreV2(dbType, logicalBackupName, storageName, blobPath));
    }

    @Override
    public void close() throws Exception {
        restClient.close();
    }
}
