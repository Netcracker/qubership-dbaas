package com.netcracker.it.dbaas.helpers;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.mongodb.MongoException;
import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.backup.v3.*;
import com.netcracker.it.dbaas.exceptions.CannotCheckData;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_CLASSIFIER_KEY;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_MICROSERVICE_NAME;
import static com.netcracker.it.dbaas.test.AbstractIT.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@AllArgsConstructor
public class BackupHelperV3 {

    public static final String TEST_NAMESPACE = "dbaas-autotests";

    private static final String BACKUPS_NAMESPACED = "api/v3/dbaas/%s/backups";
    private static final String BACKUPS = "api/v3/dbaas/backups";

    public static final String DBAAS_AUTO_TEST_1 = "dbaas_auto_test_1";
    public static final String DBAAS_AUTO_TEST_2 = "dbaas_auto_test_2";
    public static final String DBAAS_AUTO_TEST_3 = "dbaas_auto_test_3";

    public static final String BACKUPED_DATA = "backupedData";

    private static final int BACKUP_TIMEOUT_MINUTES = 10;
    private static final int RESTORE_TIMEOUT_MINUTES = 10;

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @NonNull
    private URL dbaasServiceUrl;

    @NonNull
    private DbaasHelperV3 helper;

    public void testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTargetNamespace(String databaseType) throws IOException, InterruptedException {
        testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTargetNamespace(databaseType, databaseType);
    }

    public void testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTargetNamespace(String databaseType1, String databaseType2) throws IOException, InterruptedException {
        var sourceNamespace = helper.generateTestNamespace();
        var targetNamespace = helper.generateTestNamespace();

        var namespaceBackup = testCollectBackup_TwoDbs(true, sourceNamespace, databaseType1, databaseType2);
        var backupedLogicalDatabasesInSource = namespaceBackup.getDatabasesCreatedForTest();

        var backupedLogicalDatabaseInSource1 = backupedLogicalDatabasesInSource.stream()
                .filter(it -> it.getClassifier().get(TEST_CLASSIFIER_KEY).equals(DBAAS_AUTO_TEST_1))
                .findFirst()
                .get();

        var backupedLogicalDatabaseInSource2 = backupedLogicalDatabasesInSource.stream()
                .filter(it -> it.getClassifier().get(TEST_CLASSIFIER_KEY).equals(DBAAS_AUTO_TEST_2))
                .findFirst()
                .get();

        checkConnections(false, backupedLogicalDatabasesInSource, "afterBackupData", "afterBackupData", false);

        assertTrue(namespaceBackup.canRestore(), "Backup " + namespaceBackup + " should be restorable");

        var classifierValue1 = DBAAS_AUTO_TEST_1 + "target";
        var classifierValue2 = DBAAS_AUTO_TEST_2 + "target";

        var logicalDatabasesForCleanInTarget = createLogicalDatabases(Map.of(classifierValue1, databaseType1, classifierValue2, databaseType2), targetNamespace);

        var logicalDatabaseForCleanInTarget1 = logicalDatabasesForCleanInTarget.stream()
                .filter(it -> it.getClassifier().get(TEST_CLASSIFIER_KEY).equals(classifierValue1))
                .findFirst()
                .get();

        var logicalDatabaseForCleanInTarget2 = logicalDatabasesForCleanInTarget.stream()
                .filter(it -> it.getClassifier().get(TEST_CLASSIFIER_KEY).equals(classifierValue2))
                .findFirst()
                .get();

        checkConnections(false, logicalDatabasesForCleanInTarget, "targetDataForClean", "targetDataForClean", false);

        var namespaceRestoreResult = restoreBackup(helper.getBackupDaemonAuthorization(), namespaceBackup, targetNamespace);

        assertNamespaceRestoreResultIsSuccess(namespaceBackup, namespaceRestoreResult, backupedLogicalDatabasesInSource);

        checkConnections(true, logicalDatabasesForCleanInTarget, null, null, false);

        var restoredLogicalDatabaseInTarget1 = helper.getDatabaseByClassifierAsPOJO(helper.getClusterDbaAuthorization(),
                new ClassifierBuilder().test(DBAAS_AUTO_TEST_1).ns(targetNamespace).build(),
                targetNamespace, databaseType1, 200
        );

        var restoredLogicalDatabaseInTarget2 = helper.getDatabaseByClassifierAsPOJO(helper.getClusterDbaAuthorization(),
                new ClassifierBuilder().test(DBAAS_AUTO_TEST_2).ns(targetNamespace).build(),
                targetNamespace, databaseType2, 200
        );

        log.info("Check databases in target after restoration");

        log.info("Check target databases types");
        assertEquals(backupedLogicalDatabaseInSource1.getType(), restoredLogicalDatabaseInTarget1.getType());
        assertEquals(backupedLogicalDatabaseInSource2.getType(), restoredLogicalDatabaseInTarget2.getType());

        log.info("Check target databases namespace");
        assertEquals(targetNamespace, restoredLogicalDatabaseInTarget1.getNamespace());
        assertEquals(targetNamespace, restoredLogicalDatabaseInTarget2.getNamespace());

        log.info("Check target databases are different from source databases");
        assertNotEquals(backupedLogicalDatabaseInSource1.getConnectionProperties(), restoredLogicalDatabaseInTarget1.getConnectionProperties());
        assertNotEquals(backupedLogicalDatabaseInSource2.getConnectionProperties(), restoredLogicalDatabaseInTarget2.getConnectionProperties());
        assertNotEquals(backupedLogicalDatabaseInSource1.getName(), restoredLogicalDatabaseInTarget1.getName());
        assertNotEquals(backupedLogicalDatabaseInSource2.getName(), restoredLogicalDatabaseInTarget2.getName());

        log.info("Check target databases are different from cleaned target databases");
        assertNotEquals(logicalDatabaseForCleanInTarget1.getConnectionProperties(), restoredLogicalDatabaseInTarget1.getConnectionProperties());
        assertNotEquals(logicalDatabaseForCleanInTarget2.getConnectionProperties(), restoredLogicalDatabaseInTarget2.getConnectionProperties());
        assertNotEquals(logicalDatabaseForCleanInTarget1.getName(), restoredLogicalDatabaseInTarget1.getName());
        assertNotEquals(logicalDatabaseForCleanInTarget2.getName(), restoredLogicalDatabaseInTarget2.getName());

        log.info("Check data in target databases");
        var restoredLogicalDatabasesInTarget = Arrays.asList(restoredLogicalDatabaseInTarget1, restoredLogicalDatabaseInTarget2);
        checkConnections(false, restoredLogicalDatabasesInTarget, null, BACKUPED_DATA, false);
        checkConnections(false, restoredLogicalDatabasesInTarget, null, "afterBackupData", true);
        checkConnections(false, restoredLogicalDatabasesInTarget, null, "targetDataForClean", true);
    }

    public void testCollectNamespaceBackupWithTwoLogicalDatabasesAndRestoreItToTheSameNamespaceWithDelta(String databaseType) throws IOException, InterruptedException {
        var namespace = helper.generateTestNamespace();

        var namespaceBackup = testCollectBackup_TwoDbs(true, namespace, databaseType);
        var backupedLogicalDatabases = namespaceBackup.getDatabasesCreatedForTest();

        assertTrue(namespaceBackup.canRestore(), "Backup " + namespaceBackup + " should be restorable");

        var additionalNotBackupedLogicalDatabases = helper.createDatabases(helper.getClusterDbaAuthorization(), DBAAS_AUTO_TEST_3, 201, namespace, databaseType);

        assertNotNull(additionalNotBackupedLogicalDatabases);

        log.info("Check connections to other created databases");
        checkConnections(false, additionalNotBackupedLogicalDatabases, "additionalData", "additionalData", false);
        checkConnections(false, backupedLogicalDatabases, "additionalData", "additionalData", false);

        log.info("Deleting one backuped logical database");

        var backupedLogicalDatabase = backupedLogicalDatabases.getFirst();
        var classifierWithRolesRequest = new ClassifierWithRolesRequest();

        classifierWithRolesRequest.setClassifier(backupedLogicalDatabase.getClassifier());
        classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
        classifierWithRolesRequest.setOriginService((String) backupedLogicalDatabase.getClassifier().get("microserviceName"));

        helper.deleteDatabasesByClassifierRequest(namespace, databaseType, classifierWithRolesRequest, 200);

        log.info("Check connections to deleted backuped logical database");
        checkConnections(true, List.of(backupedLogicalDatabase), null, null, false);

        log.info("Start restore");

        var namespaceRestoreResult = restoreBackup(helper.getBackupDaemonAuthorization(), namespaceBackup);

        assertNamespaceRestoreResultIsSuccess(namespaceBackup, namespaceRestoreResult, backupedLogicalDatabases);

        log.info("Check connections to databases in backup");
        checkConnections(false, backupedLogicalDatabases, null, BACKUPED_DATA, false);
        checkConnections(false, backupedLogicalDatabases, null, "additionalData", true);

        log.info("Check connections to databases deleted by restore");
        checkConnections(true, additionalNotBackupedLogicalDatabases, null, null, false);
    }

    protected void assertNamespaceRestoreResultIsSuccess(NamespaceBackupV3 namespaceBackup,
                                                         NamespaceRestoreResult namespaceRestoreResult,
                                                         List<DatabaseResponse> backupedLogicalDatabases) {

        assertEquals(Status.SUCCESS, namespaceRestoreResult.getStatus(), String.join("\n", namespaceRestoreResult.getFailReasons()));

        Assertions.assertNotNull(namespaceRestoreResult.getRestoreResults());
        Assertions.assertFalse(namespaceRestoreResult.getRestoreResults().isEmpty());

        Assertions.assertTrue(
                namespaceRestoreResult.getRestoreResults().stream()
                        .allMatch(restoreResult -> Status.SUCCESS.equals(restoreResult.getStatus()))
        );

        var allChangedNameDbs = namespaceRestoreResult.getRestoreResults().stream()
                .map(RestoreResult::getChangedNameDb)
                .filter(Objects::nonNull)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (!allChangedNameDbs.isEmpty()) {

            var nextAndPreviousChangedLogicalDatabaseNames = allChangedNameDbs.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            Map.Entry::getValue,
                            Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                    ));

            Assertions.assertTrue(
                    nextAndPreviousChangedLogicalDatabaseNames.entrySet().stream()
                            .allMatch(entry -> entry.getValue().size() == 1),

                    String.format("Namespace restore result of namespace backup with id %s has the same changed database name for more than one logical database: %s",
                            namespaceBackup.getId(), nextAndPreviousChangedLogicalDatabaseNames
                    )
            );

            var backupedLogicalDatabaseIdsWithoutChangedName = backupedLogicalDatabases.stream()
                    .filter(logicalDatabase -> !allChangedNameDbs.containsKey(logicalDatabase.getName())
                            && !allChangedNameDbs.containsKey(logicalDatabase.getConnectionPropertyAsString("resourcePrefix"))
                    )
                    .map(DatabaseResponse::getId)
                    .toList();

            Assertions.assertTrue(backupedLogicalDatabaseIdsWithoutChangedName.isEmpty(),
                    String.format("Namespace restore result of namespace backup with id %s does not have changed names for backuped logical databases with ids %s",
                            namespaceBackup.getId(), backupedLogicalDatabaseIdsWithoutChangedName
                    )
            );
        }
    }

    public void checkConnections(Boolean expectCannotConnect, List<DatabaseResponse> dbs, String setData, String checkData, Boolean expectCannotCheckData) {
        dbs.forEach(
                db -> {
                    try {
                        log.info("Check connection to created database {}", db);
                        switch (db.getType()) {
                            case MONGODB_TYPE ->
                                    helper.checkConnectionMongo(db, expectCannotConnect, setData, checkData);
                            case POSTGRES_TYPE -> helper.checkConnectionPostgres(db, setData, checkData);
                            case OPENSEARCH_TYPE -> helper.checkConnectionOpensearch(db, setData, checkData);
                            case CASSANDRA_TYPE -> helper.checkConnectionCassandra(db, setData, checkData);
                            case CLICKHOUSE_TYPE -> helper.checkConnectionClickhouse(db, setData, checkData);
                            case ARANGODB_TYPE -> helper.checkConnectionArangodb(db, setData, checkData);
                            case null, default -> fail("Database " + db + " has unknown type");
                        }
                        if (expectCannotConnect || expectCannotCheckData) {
                            fail("Expected exception to be thrown while connecting to " + db);
                        }
                    } catch (CannotConnect | SQLException | MongoException | IOException e) {
                        if (expectCannotConnect) {
                            log.info("Exception thrown as expected", e);
                        } else {
                            throw new RuntimeException(e);
                        }
                    } catch (CannotCheckData e) {
                        if (expectCannotCheckData) {
                            log.info("Exception thrown as expected", e);
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                }
        );
    }

    public NamespaceBackupV3 testCollectBackup_TwoDbs(Boolean checkConnectionAndSetData, String namespace, String... types) throws IOException, InterruptedException {
        return prepareAndCollectBackup(checkConnectionAndSetData, new HashMap<>() {{
            put(DBAAS_AUTO_TEST_1, types[0]);
            put(DBAAS_AUTO_TEST_2, types.length > 1 ? types[1] : types[0]);
        }}, namespace);
    }

    public NamespaceBackupV3 prepareAndCollectBackup(Boolean checkConnectionAndSetData,
                                                     Map<String, String> nameToType,
                                                     String namespace) throws IOException {
        List<DatabaseResponse> created = createLogicalDatabases(nameToType, namespace);
        if (checkConnectionAndSetData) {
            checkConnections(false, created, BACKUPED_DATA, BACKUPED_DATA, false);
        }
        log.info("Start backup");
        NamespaceBackupV3 backup = collectBackup(helper.getBackupDaemonAuthorization(), namespace, false);
        backup.setDatabasesCreatedForTest(created);
        int expectNumberBackups = new HashSet<>(nameToType.values()).size();
        log.info("Backup: {}", backup);
        assertThat(backup.getBackups(), Matchers.<List<DatabasesBackup>>allOf(
                hasSize(equalTo(expectNumberBackups)),
                everyItem(notEmptyLocalId())
        ));
        backup.getBackups().forEach(
                it -> assertEquals(Status.SUCCESS, it.getStatus(), "Status should be successful")
        );
        List<String> expectNames = created.stream().map(DatabaseResponse::getName).toList();
        List<String> resultedNames = backup.getBackups().stream().flatMap(backup1 -> backup1.getDatabases().stream()).collect(Collectors.toList());
        expectNames.forEach(
                it -> {
                    assertTrue(resultedNames.contains(it),
                            "Every expected database should be present once, expects: " + expectNames);
                    resultedNames.remove(it);
                }
        );
        assertThat("Should not be extra databases in backup: " + resultedNames, resultedNames, is(empty()));
        return backup;
    }

    public List<DatabaseResponse> createLogicalDatabases(Map<String, String> testClassifierValuesAndDatabaseTypes, String namespace) {
        log.info("Create logical databases");

        return testClassifierValuesAndDatabaseTypes.entrySet().stream().map(entry -> {
            try {
                var testClassifierValue = entry.getKey();
                var databaseType = entry.getValue();

                var prefixName = "dbaas_autotests";
                Map<String, Object> settingsMap = null;

                if (OPENSEARCH_TYPE.equalsIgnoreCase(databaseType)) {
                    prefixName = null;
                    settingsMap = Collections.singletonMap("resourcePrefix", true);
                }

                var databaseCreateRequest = DatabaseCreateRequestV3.builder(
                                new ClassifierBuilder().test(testClassifierValue).ns(namespace).build(),
                                databaseType
                        )
                        .type(databaseType)
                        .namePrefix(prefixName)
                        .settings(settingsMap)
                        .originService(TEST_MICROSERVICE_NAME)
                        .backupDisabled(false)
                        .build();

                var createdDatabase = helper.createDatabase(databaseCreateRequest, 201);

                assertNotNull(createdDatabase);

                return createdDatabase;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    public BaseMatcher<DatabasesBackup> notEmptyLocalId() {
        return new BaseMatcher<>() {
            @Override
            public boolean matches(Object o) {
                return !StringUtils.isEmpty(((DatabasesBackup) o).getLocalId());
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("databases backup with not empty local id");
            }

            @Override
            public void describeMismatch(final Object item, final
            Description description) {
                description.appendText("was databases backup with local id: \"").appendValue(((DatabasesBackup) item).getLocalId()).appendText("\"");
            }
        };
    }

    public Request collectBackupRequest(String authorization, String namespace, Boolean ignoreNotBackupableDatabases) {
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format(BACKUPS_NAMESPACED, namespace) + "/collect" + (!ignoreNotBackupableDatabases ? "" : "?ignoreNotBackupableDatabases=true"))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create("", null))
                .build();
    }

    public Request restoreBackupRequest(String authorization, NamespaceBackupV3 backup, String targetNamespace) {
        return new Request.Builder()
                .url(dbaasServiceUrl + BACKUPS + "/" + backup.getId() + "/restorations"
                        + (targetNamespace != null ? ("?targetNamespace=" + targetNamespace) : ""))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create("", null))
                .build();
    }


    public Request oldRestoreBackupRequest(String authorization, NamespaceBackupV3 backup) {
        return new Request.Builder()
                .url(dbaasServiceUrl + "api/v3/dbaas/" + backup.getNamespace() + "/backups" + "/" + backup.getId() + "/restore")
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .post(RequestBody.create("", null))
                .build();
    }


    public NamespaceRestoreResult restoreBackup(String authorization, NamespaceBackupV3 backup) throws IOException {
        return restoreBackup(authorization, backup, null, false);
    }

    public NamespaceRestoreResult restoreBackup(String authorization, NamespaceBackupV3 backup, String targetNamespace) throws IOException {
        return restoreBackup(authorization, backup, targetNamespace, false);
    }

    public NamespaceRestoreResult restoreBackup(String authorization, NamespaceBackupV3 backup, Boolean old) throws IOException {
        return restoreBackup(authorization, backup, null, old);
    }

    public NamespaceRestoreResult restoreBackup(String authorization, NamespaceBackupV3 backup, String targetNamespace, Boolean old) throws IOException {
        if (targetNamespace != null && old) {
            fail("Old dbaas does not support target namespace");
        }

        var request = old ? oldRestoreBackupRequest(authorization, backup) : restoreBackupRequest(authorization, backup, targetNamespace);

        try (var response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);

            var responseBodyStr = response.body().string();

            log.debug("Response body: {}", responseBodyStr);

            assertThat(response.code(), oneOf(200, 202));

            switch (response.code()) {
                case 202:
                    log.info("Track restore backup");

                    var location = response.header("Location");

                    return Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(RESTORE_TIMEOUT_MINUTES))).get(() -> {
                        var trackResponse = trackRestoration(location);

                        Assertions.assertNotEquals(Status.PROCEEDING, trackResponse.getStatus());

                        return trackResponse;
                    });
                case 200:
                    log.info("Restore result received");

                    return new Gson().fromJson(responseBodyStr, new TypeToken<NamespaceRestoreResult>() {
                    }.getType());
                default:
                    throw new AssertionError("Unknown code: " + response.code());
            }
        }
    }

    public NamespaceBackupV3 collectBackup(String authorization, String namespace, boolean ignoreNotBackupableDatabases) throws IOException {
        var request = collectBackupRequest(authorization, namespace, ignoreNotBackupableDatabases);

        try (var response = okHttpClient.newCall(request).execute()) {

            log.info("Response: {}", response);

            var responseBodyStr = response.body().string();

            log.debug("Response body: {}", responseBodyStr);

            assertThat(response.code(), oneOf(202, 201, 501));

            switch (response.code()) {
                case 202:
                    log.info("Track backup collect");

                    var location = response.header("Location");

                    return Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(BACKUP_TIMEOUT_MINUTES))).get(() -> {
                        var trackResponse = trackBackup(location);

                        Assertions.assertNotEquals(NamespaceBackupV3.Status.PROCEEDING, trackResponse.getStatus());

                        return trackResponse;
                    });
                case 201:
                    log.info("Backup received");

                    return new Gson().fromJson(responseBodyStr, new TypeToken<NamespaceBackupV3>() {
                    }.getType());
                case 501:
                    throw new HttpClientErrorException(HttpStatus.NOT_IMPLEMENTED, responseBodyStr);
                default:
                    throw new AssertionError("Unknown code: " + response.code());
            }

        }
    }

    protected NamespaceRestoreResult trackRestoration(String location) throws IOException {
        log.info("Track backup restoration on {}", location);

        try (var response = okHttpClient.newCall(new Request.Builder()
                .url(helper.urlNormalize(dbaasServiceUrl.toString(), location))
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build()).execute()) {

            log.info("Response: {}", response);

            var responseBodyStr = response.body().string();

            log.debug("Response body: {}", responseBodyStr);

            assertThat(response.code(), is(200));

            return new Gson().fromJson(responseBodyStr, new TypeToken<NamespaceRestoreResult>() {
            }.getType());
        }
    }

    protected NamespaceBackupV3 trackBackup(String location) throws IOException {
        log.info("Track backup collection on {}", location);

        try (var response = okHttpClient.newCall(new Request.Builder()
                .url(dbaasServiceUrl + location)
                .addHeader("Authorization", "Basic " + helper.getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build()).execute()) {

            log.info("Response: {}", response);

            var body = response.body().string();

            log.debug("Response body: {}", body);

            assertThat(response.code(), is(200));

            return new Gson().fromJson(body, new TypeToken<NamespaceBackupV3>() {
            }.getType());
        }
    }

    public NamespaceBackupDeletion deleteBackup(String authorization, NamespaceBackupV3 backup) {
        Request deleteRequest = deleteBackupRequest(authorization, backup);
        try (Response response = okHttpClient.newCall(deleteRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), oneOf(200));
            if (response.code() == 200) {
                log.info("Deletion result received");
                return new Gson().fromJson(body, new TypeToken<NamespaceBackupDeletion>() {
                }.getType());
            } else {
                throw new AssertionError("Unknown code: " + response.code());
            }
        } catch (IOException e) {
            log.error("Error during delete backup", e);
        }
        return null;
    }

    public Request deleteBackupRequest(String authorization, NamespaceBackupV3 backup) {
        return new Request.Builder()
                .url(dbaasServiceUrl + "api/v3/dbaas/" + backup.getNamespace() + "/backups/" + backup.getId())
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .delete()
                .build();
    }

    public void assertBackupNotExist(String authorization, NamespaceBackupV3 backup) {
        Request getInfoRequest = getBackupRequest(authorization, backup);
        try (Response response = okHttpClient.newCall(getInfoRequest).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), is(404));
        } catch (IOException e) {
            log.error("Error during checking backup existence", e);
        }
    }

    public Request getBackupRequest(String authorization, NamespaceBackupV3 backup) {
        return new Request.Builder()
                .url(dbaasServiceUrl + "api/v3/dbaas/" + backup.getNamespace() + "/backups/" + backup.getId())
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                .get()
                .build();
    }
}
