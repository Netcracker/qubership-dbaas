package com.netcracker.it.dbaas.helpers;

import com.google.common.reflect.TypeToken;
import com.google.gson.*;
import com.netcracker.it.dbaas.entity.backup.v1.*;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.JSON;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE_PATTERN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@AllArgsConstructor
public class BackupHelperV1 {
    public static final String STORAGE_NAME = "minio";
    public static final String BLOB_PATH = "tmp/a/b/c";
    public static final String DIGEST = "digest";
    public static final String BACKUP_METADATA = "metadata";

    public final static RetryPolicy<Object> BACKUP_RESTORE_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(5)).withMaxDuration(Duration.ofMinutes(10));
    public final static RetryPolicy<Object> BACKUP_DELETE_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(1));

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(
                    Instant.class,
                    (JsonSerializer<Instant>) (src, type, ctx) ->
                            new JsonPrimitive(src.toString())
            )
            .registerTypeAdapter(
                    Instant.class,
                    (JsonDeserializer<Instant>) (json, type, ctx) ->
                            Instant.parse(json.getAsString()))
            .create();

    @NonNull
    private DbaasHelperV3 helper;

    private URL dbaasServiceUrl() {
        return helper.getDbaasServiceUrl();
    }

    public void deleteBackupRestore() {
        List<String> restoreNamesToDelete = getAllRestoreNames()
                .stream().filter(r -> TEST_NAMESPACE_PATTERN.matcher(r).matches())
                .toList();
        if (!restoreNamesToDelete.isEmpty()) {
            deleteAllRestoreByNames(restoreNamesToDelete);
        }
        List<String> backupNamesToDelete = getAllBackupNames()
                .stream().filter(b -> TEST_NAMESPACE_PATTERN.matcher(b).matches())
                .toList();
        if (!backupNamesToDelete.isEmpty()) {
            deleteAllBackupByNames(backupNamesToDelete);
        }
    }

    public static DatabaseType mapDbType(String type) {
        return switch (type) {
            case "postgresql" -> DatabaseType.POSTGRESQL;
            case "arangodb" -> DatabaseType.ARANGODB;
            case "clickhouse" -> DatabaseType.CLICKHOUSE;
            case "mongodb" -> DatabaseType.MONGODB;
            case "cassandra" -> DatabaseType.CASSANDRA;
            default -> throw new IllegalArgumentException("Unknown database type: " + type);
        };
    }

    public BackupResponse runBackupAndWait(BackupRequest backupRequest, boolean dryRun) {
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
                        var backup = getBackup(backupRequest.getBackupName(), 200);
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

    public RestoreResponse runRestoreAndWait(String backupName, RestoreRequest restoreRequest, boolean dryRun) {
        Request request = startRestoreRequestAllowParallel(helper.getBackupDaemonAuthorization(), backupName, restoreRequest, dryRun);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.info("Response body: {}", body);
            assertThat(response.code(), oneOf(200, 202, 409));
            switch (response.code()) {
                case 200:
                    log.info("Restore result received");
                    return gson.fromJson(body, new TypeToken<RestoreResponse>() {
                    }.getType());
                case 202:
                    log.info("Restore status checking");
                    return Failsafe.with(BACKUP_RESTORE_RETRY_POLICY).get(() -> {
                        var restore = getRestore(restoreRequest.getRestoreName());
                        RestoreStatus status = restore.getStatus();
                        assertTrue(
                                RestoreStatus.COMPLETED == status ||
                                        RestoreStatus.FAILED == status
                        );
                        return restore;
                    });
                case 409:
                    return null;
            }
        } catch (IOException e) {
            log.error("Error during restore process", e);
        }
        return null;
    }

    public BackupResponse startBackup(BackupRequest backupRequest, boolean dryRun, int code) {
        Request startBackupRequest = startBackupRequest(helper.getBackupDaemonAuthorization(), backupRequest, dryRun);
        try (Response response = okHttpClient.newCall(startBackupRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(code));
            log.info("Backup information result received");
            return gson.fromJson(body, new com.google.gson.reflect.TypeToken<BackupResponse>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during start backup", e);
        }
        return null;
    }


    public RestoreResponse startRestore(String backupName, RestoreRequest restoreRequest, boolean dryRun, int code) {
        Request request = startRestoreRequest(helper.getBackupDaemonAuthorization(), backupName, restoreRequest, dryRun);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(code));
            log.info("Restore status result received");
            return gson.fromJson(body, new com.google.gson.reflect.TypeToken<RestoreResponse>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during start restore", e);
        }
        return null;
    }

    public BackupResponse getBackup(String backupName, int code) {
        Request request = getBackupRequest(helper.getBackupDaemonAuthorization(), backupName);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(code));
            log.info("Backup information result received");
            if (code == 404)
                return null;
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

    public RestoreResponse getRestore(String restoreName) {
        Request restoreInformationRequest = getRestoreRequest(helper.getBackupDaemonAuthorization(), restoreName);
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

    public Map<String, Object> getBackupMetadata(String backupName) {
        Request backupMetadataRequest = getBackupMetadataRequest(helper.getBackupDaemonAuthorization(), backupName);
        try (Response response = okHttpClient.newCall(backupMetadataRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), equalTo(200));
            log.info("Backup metadata result received");
            BackupResponse backupResponse = gson.fromJson(body, new com.google.gson.reflect.TypeToken<BackupResponse>() {
            }.getType());
            return Map.of(
                    DIGEST, Objects.requireNonNull(response.header("Digest")),
                    BACKUP_METADATA, backupResponse
            );
        } catch (IOException e) {
            log.error("Error during requesting backup metadata", e);
        }
        return null;
    }

    public void uploadBackupMetadata(String digest, BackupResponse metadata, int code) {
        Request exportBackupMetadata = uploadBackupMetadataRequest(helper.getBackupDaemonAuthorization(), digest, metadata);
        try (Response response = okHttpClient.newCall(exportBackupMetadata).execute()) {
            log.info("Response: {}", response);
            log.info("Response body: {}", response.body().string());
            assertThat(response.code(), equalTo(code));
            log.info("upload backup metadata finished");
        } catch (IOException e) {
            log.error("Error during uploading backup metadata");
        }
    }

    public void deleteBackup(String backupName, boolean force) {
        Request deleteBackup = deleteBackupRequest(helper.getBackupDaemonAuthorization(), backupName, force);
        try (Response response = okHttpClient.newCall(deleteBackup).execute()) {
            log.info("Response: {}", response);
            log.info("Response body: {}", response.body().string());
            assertThat(response.code(), oneOf(202, 204));
            switch (response.code()) {
                case 204:
                    break;
                case 202:
                    log.info("Backup status checking");
                    Failsafe.with(BACKUP_DELETE_RETRY_POLICY).run(() -> {
                        BackupStatus status = getBackupStatus(backupName).getStatus();
                        assertTrue(
                                BackupStatus.DELETED == status ||
                                        BackupStatus.FAILED == status
                        );
                    });
                    break;
            }
            log.info("Delete backup finished");
        } catch (IOException e) {
            log.error("Error during deleting backup");
        }
    }

    public void deleteBackupFromDb(String backupName) {
        Request deleteBackup = deleteBackupRequest(backupName);
        try (Response response = okHttpClient.newCall(deleteBackup).execute()) {
            log.info("Response: {}", response);
            log.info("Response body: {}", response.body().string());
            assertThat(response.code(), equalTo(204));
            log.info("Delete backup finished");
        } catch (IOException e) {
            log.error("Error during deleting backup");
        }
    }

    public List<String> getAllBackupNames() {
        Request getBackupNames = getAllBackupNameRequest();
        try (Response response = okHttpClient.newCall(getBackupNames).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.info("Response body: {}", body);
            assertThat(response.code(), equalTo(200));
            log.info("Get all backup names finished");
            return gson.fromJson(body, new com.google.gson.reflect.TypeToken<List<String>>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during getting all backup names", e);
        }
        return null;
    }

    public List<String> getAllRestoreNames() {
        Request getRestoreNames = getAllRestoreNameRequest();
        try (Response response = okHttpClient.newCall(getRestoreNames).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.info("Response body: {}", body);
            assertThat(response.code(), equalTo(200));
            log.info("Get all restore names finished");
            return gson.fromJson(body, new com.google.gson.reflect.TypeToken<List<String>>() {
            }.getType());
        } catch (IOException e) {
            log.error("Error during getting all restore names", e);
        }
        return null;
    }

    public void deleteAllBackupByNames(List<String> backupNames) {
        Request deleteBackup = deleteAllBackupByNamesRequest(backupNames);
        try (Response response = okHttpClient.newCall(deleteBackup).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.info("Response body: {}", body);
            assertThat(response.code(), equalTo(204));
            log.info("Delete all backup by names finished successfully");
        } catch (IOException e) {
            log.error("Error during deleting all backup", e);
        }
    }

    public void deleteAllRestoreByNames(List<String> restoreNames) {
        Request deleteRestore = deleteAllRestoreByNamesRequest(restoreNames);
        try (Response response = okHttpClient.newCall(deleteRestore).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.info("Response body: {}", body);
            assertThat(response.code(), equalTo(204));
            log.info("Delete all restore by names finished successfully");
        } catch (IOException e) {
            log.error("Error during deleting all restore", e);
        }
    }

    public Request getBackupRequest(String authorization, String backupName) {
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s", backupName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request backupStatusRequest(String authorization, String backupName) {
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s/status", backupName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request startBackupRequest(String authorization, BackupRequest backup, boolean dryRun) {
        String reqJson = new GsonBuilder().create().toJson(backup);
        return new Request.Builder()
                .url(dbaasServiceUrl() + "api/backups/v1/backup?dryRun=" + dryRun)
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create(reqJson, JSON))
                .build();
    }

    public Request startRestoreRequest(String authorization, String backupName, RestoreRequest restore, boolean dryRun) {
        String reqJson = new GsonBuilder().create().toJson(restore);
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s/restore?dryRun=%s", backupName, dryRun))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create(reqJson, JSON))
                .build();
    }

    public Request startRestoreRequestAllowParallel(String authorization, String backupName, RestoreRequest restore, boolean dryRun) {
        String reqJson = new GsonBuilder().create().toJson(restore);
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s/restore/allowParallel?dryRun=%s", backupName, dryRun))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create(reqJson, JSON))
                .build();
    }

    public Request getRestoreRequest(String authorization, String restoreName) {
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/restore/%s", restoreName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request getBackupMetadataRequest(String authorization, String backupName) {
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s/metadata", backupName))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request uploadBackupMetadataRequest(String authorization, String digest, BackupResponse metadata) {
        String reqJson = gson.toJson(metadata);
        return new Request.Builder()
                .url(dbaasServiceUrl() + "api/backups/v1/operation/uploadMetadata")
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .addHeader("Digest", digest)
                .post(RequestBody.create(reqJson, JSON))
                .build();
    }

    public Request deleteBackupRequest(String authorization, String backupName, boolean force) {
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s?force=%s", backupName, force))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .delete()
                .build();
    }

    public Request deleteBackupRequest(String backupName) {
        return new Request.Builder()
                .url(dbaasServiceUrl() + String.format("api/backups/v1/backup/%s/forceDelete", backupName))
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .delete()
                .build();
    }

    public Request getAllBackupNameRequest() {
        return new Request.Builder()
                .url(dbaasServiceUrl() + "api/backups/v1/backup/getAllBackupNames")
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request getAllRestoreNameRequest() {
        return new Request.Builder()
                .url(dbaasServiceUrl() + "api/backups/v1/restore/getAllRestoreNames")
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }

    public Request deleteAllBackupByNamesRequest(List<String> backupNames) {
        String joinedNames = String.join(",", backupNames);
        String queryParam = joinedNames.isBlank() ? "" : "?backupNames=" + joinedNames;

        return new Request.Builder()
                .url(dbaasServiceUrl() + "api/backups/v1/backup/deleteAll" + queryParam)
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .delete()
                .build();
    }

    public Request deleteAllRestoreByNamesRequest(List<String> restoreNames) {
        String joinedNames = String.join(",", restoreNames);
        String queryParam = joinedNames.isBlank() ? "" : "?restoreNames=" + joinedNames;

        return new Request.Builder()
                .url(dbaasServiceUrl() + "api/backups/v1/restore/deleteAll" + queryParam)
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .delete()
                .build();
    }
}
