package com.netcracker.it.dbaas.helpers;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.netcracker.it.dbaas.entity.backup.v1.*;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.*;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.JSON;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@AllArgsConstructor
public class BackupHelperV1 {
    public static final String DEFAULT_STORAGE = "minio";
    public static final String DEFAULT_BLOB_PATH = "tmp/a/b/c";

    public final static RetryPolicy<Object> BACKUP_RESTORE_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(30)).withMaxDuration(Duration.ofMinutes(10));

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, ctx) ->
                    Instant.parse(json.getAsString()))
            .create();

    @NonNull
    private URL dbaasServiceUrl;

    @NonNull
    private DbaasHelperV3 helper;

    public BackupRequest getBackupRequest(String backupName,
                                          String storageName,
                                          String blobPath,
                                          List<String> namespaces,
                                          ExternalDatabaseStrategy strategy,
                                          boolean ignoreNotBackupableDatabases) {
        BackupRequest backupRequest = new BackupRequest();
        backupRequest.setBackupName(backupName);
        backupRequest.setStorageName(storageName);
        backupRequest.setBlobPath(blobPath);

        Filter filter = new Filter();
        filter.setNamespace(namespaces);
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        backupRequest.setFilterCriteria(filterCriteria);
        backupRequest.setExternalDatabaseStrategy(strategy);
        backupRequest.setIgnoreNotBackupableDatabases(ignoreNotBackupableDatabases);
        return backupRequest;
    }

    public RestoreRequest getRestoreRequest(String restoreName,
                                            String storageName,
                                            String blobPath,
                                            List<String> namespaces,
                                            Map<String, String> namespaceMap,
                                            Map<String, String> tenantMap,
                                            ExternalDatabaseStrategy strategy) {
        RestoreRequest restoreRequest = new RestoreRequest();
        restoreRequest.setRestoreName(restoreName);
        restoreRequest.setStorageName(storageName);
        restoreRequest.setBlobPath(blobPath);

        Filter filter = new Filter();
        filter.setNamespace(namespaces);
        FilterCriteria filterCriteria = new FilterCriteria();
        filterCriteria.setFilter(List.of(filter));

        Mapping mapping = new Mapping();
        mapping.setNamespaces(namespaceMap);
        mapping.setTenants(tenantMap);

        restoreRequest.setMapping(mapping);
        restoreRequest.setFilterCriteria(filterCriteria);
        restoreRequest.setExternalDatabaseStrategy(strategy);
        return restoreRequest;
    }

    public BackupResponse executeBackup(BackupRequest backupRequest, boolean dryRun) {
        Request startBackupRequest = startBackupRequest(helper.getBackupDaemonAuthorization(), backupRequest, dryRun);
        try (Response response = okHttpClient.newCall(startBackupRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), oneOf(200, 202));

            switch (response.code()) {
                case 200:
                    log.info("Backup result received");
                    return gson.fromJson(body, new TypeToken<BackupResponse>() {
                    }.getType());
                case 202:
                    log.info("Backup status checking");
                    return Failsafe.with(BACKUP_RESTORE_RETRY_POLICY).get(() -> {
                        var backup = getBackupInformation(backupRequest.getBackupName());
                        BackupStatus status = backup.getStatus();
                        assertTrue(
                                BackupStatus.COMPLETED == status ||
                                        BackupStatus.FAILED == status
                        );
                        return backup;
                    });
            }
        } catch (IOException e) {
            log.error("Error during backup process", e);
        }
        return null;
    }

    public RestoreResponse executeRestore(String backupName, RestoreRequest restoreRequest, boolean dryRun) {
        Request request = startRestoreRequest(helper.getBackupDaemonAuthorization(), backupName, restoreRequest, dryRun);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.info("Response body: {}", body);
            assertThat(response.code(), oneOf(200, 202));
            switch (response.code()) {
                case 200:
                    log.info("Restore result received");
                    return gson.fromJson(body, new TypeToken<RestoreResponse>() {
                    }.getType());
                case 202:
                    log.info("Restore status checking");
                    return Failsafe.with(BACKUP_RESTORE_RETRY_POLICY).get(() -> {
                        var restore = getRestoreInformation(restoreRequest.getRestoreName());
                        RestoreStatus status = restore.getStatus();
                        assertTrue(
                                RestoreStatus.COMPLETED == status ||
                                        RestoreStatus.FAILED == status
                        );
                        return restore;
                    });
            }
        } catch (IOException e) {
            log.error("Error during starting restore", e);
        }
        return null;
    }


    public BackupResponse getBackupInformation(String backupName) {
        Request request = backupInformationRequest(helper.getBackupDaemonAuthorization(), backupName);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(200));
            log.info("Backup information result received");
            return gson.fromJson(body, new com.google.gson.reflect.TypeToken<BackupResponse>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during requesting backup information", e);
        }
        return null;
    }

    public BackupStatusResponse getBackupStatus(String backupName) {
        Request backupStatusRequest = backupStatusRequest(helper.getBackupDaemonAuthorization(), backupName);
        try (Response response = okHttpClient.newCall(backupStatusRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(200));
            log.info("Backup status result received");
            return gson.fromJson(body, new TypeToken<BackupStatusResponse>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during checking backup status", e);
        }
        return null;
    }

    public RestoreResponse getRestoreInformation(String restoreName) {
        Request restoreInformationRequest = restoreInformationRequest(helper.getBackupDaemonAuthorization(), restoreName);
        try (Response response = okHttpClient.newCall(restoreInformationRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(200));
            log.info("Restore status result received");
            return gson.fromJson(body, new TypeToken<RestoreResponse>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during requesting restore", e);
        }
        return null;
    }

    public Request backupInformationRequest(String authorization, String backupName) {
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format("api/backups/v1/backup/%s", backupName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request backupStatusRequest(String authorization, String backupName) {
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format("api/backups/v1/backup/%s/status", backupName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request startBackupRequest(String authorization, BackupRequest backup, boolean dryRun) {
        String reqJson = new GsonBuilder().create().toJson(backup);
        return new Request.Builder()
                .url(dbaasServiceUrl + "api/backups/v1/backup?dryRun=" + dryRun)
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create(reqJson, JSON))
                .build();
    }

    public Request startRestoreRequest(String authorization, String backupName, RestoreRequest restore, boolean dryRun) {
        String reqJson = new GsonBuilder().create().toJson(restore);
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format("api/backups/v1/backup/%s/restore?dryRun=%s", backupName, dryRun))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create(reqJson, JSON))
                .build();
    }

    public Request restoreInformationRequest(String authorization, String restoreName) {
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format("api/backups/v1/restore/%s", restoreName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }
}
