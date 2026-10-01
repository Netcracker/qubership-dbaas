package com.netcracker.cloud.dbaas.integration;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.dto.API_VERSION;
import com.netcracker.cloud.dbaas.dto.backup.DeleteResult;
import com.netcracker.cloud.dbaas.dto.backup.Status;
import com.netcracker.cloud.dbaas.dto.backupV2.BackupRequest;
import com.netcracker.cloud.dbaas.dto.backupV2.BackupResponse;
import com.netcracker.cloud.dbaas.dto.backupV2.Filter;
import com.netcracker.cloud.dbaas.dto.backupV2.FilterCriteria;
import com.netcracker.cloud.dbaas.dto.backupV2.RestoreRequest;
import com.netcracker.cloud.dbaas.dto.backupV2.RestoreResponse;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.Backup;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupDatabase;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.LogicalBackup;
import com.netcracker.cloud.dbaas.dto.migration.RegisterDatabaseResponseBuilder;
import com.netcracker.cloud.dbaas.dto.userrestore.RestoreUsersRequest;
import com.netcracker.cloud.dbaas.dto.userrestore.RestoreUsersResponse;
import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import com.netcracker.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import com.netcracker.cloud.dbaas.dto.v3.PasswordChangeRequestV3;
import com.netcracker.cloud.dbaas.dto.v3.RegisterDatabaseRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import com.netcracker.cloud.dbaas.enums.BackupStatus;
import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.enums.RestoreStatus;
import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import com.netcracker.cloud.dbaas.exceptions.PasswordChangeFailedException;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.integration.config.WireMockResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BackupRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.RestoreRepository;
import com.netcracker.cloud.dbaas.rest.AdapterResponseExceptionMapper;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.rest.SecureDbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.DynamicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import com.netcracker.cloud.dbaas.service.*;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.*;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
@QuarkusTestResource(PostgresqlContainerResource.class)
class AdapterErrorHandlingTest {

    @Inject
    DbaasAdapterRESTClientFactory factory;
    @Inject
    AdapterActionTrackerClient adapterActionTrackerClient;
    @ConfigProperty(name = "wiremock.url")
    String wiremockAddress;
    @Inject
    UserService userService;
    @Inject
    PasswordRotationService passwordRotationService;
    @Inject
    MigrationService migrationService;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;
    @InjectMock
    BalancingRulesService balancingRulesService;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DbBackupV2Service dbBackupV2Service;
    @Inject
    BackupRepository backupRepository;
    @Inject
    RestoreRepository restoreRepository;

    @AfterEach
    void cleanup() {
        WireMockResource.getServer().resetAll();
        databaseRegistryDbaasRepository.findAllDatabaseRegistersAnyLogType()
                .forEach(databaseRegistryDbaasRepository::delete);
        // Restores must be deleted before backups: restore_database has a FK to backup_database.
        restoreRepository.listAll().forEach(restoreRepository::delete);
        backupRepository.listAll().forEach(backupRepository::delete);
    }

    @Test
    void testAdapterSupports_404_returnsDefaults() {
        DbaasAdapter adapter = createWireMockAdapter();

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .willReturn(aResponse().withStatus(NOT_FOUND.getStatusCode()))
        );

        // 404 means the adapter has not implemented /supports; the contract
        // says fall back to the defaults, so isUsersSupported() must return true.
        assertTrue(adapter.isUsersSupported(),
                "A 404 on /supports should return the default value (true for the 'users' feature)");
    }

    @Test
    void testRestoreUsers_adapterError_collectedInUnsuccessful() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID))
                .thenReturn(wiremockAdapter);

        WireMockResource.getServer().stubFor(
                put(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/users/.*"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "error": "INTERNAL_ERROR",
                                          "message": "Adapter failure"
                                        }
                                        """))
        );

        Database database = new DatabaseBuilder().registry().build();
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().getFirst());

        RestoreUsersRequest request = new RestoreUsersRequest();
        request.setClassifier(database.getDatabaseRegistry().getFirst().getClassifier());
        request.setType(PG_TYPE);

        RestoreUsersResponse response = userService.restoreUsers(request);

        assertEquals(0, response.getSuccessfully().size(),
                "No CP should be marked successfully restored when the adapter returns 500");
        assertEquals(1, response.getUnsuccessfully().size(),
                "The failed CP should appear in the unsuccessfully list, not propagate as an exception");
        assertEquals(ADMIN_USER_NAME,
                response.getUnsuccessfully().getFirst().getConnectionProperties().get("username"),
                "The entry in unsuccessfully should carry the original connection properties");
    }

    @Test
    void testDatabasesList_405_throwsNotAllowedException() {
        DbaasAdapter adapter = createWireMockAdapter();

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases"))
                        .willReturn(aResponse()
                                .withStatus(METHOD_NOT_ALLOWED.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Method Not Allowed"
                                        }
                                        """))
        );

        NotAllowedException ex = assertThrows(NotAllowedException.class, adapter::getDatabases,
                "A 405 on /databases should throw NotAllowedException, not AdapterException");
        assertTrue(ex.getMessage().contains("getDatabases"),
                "The NotAllowedException message should mention the missing 'getDatabases' API");
    }

    @Test
    void testDatabasesList_500_throwsAdapterException() {
        DbaasAdapter adapter = createWireMockAdapter();

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Internal adapter error"
                                        }
                                        """))
        );

        AdapterException ex = assertThrows(AdapterException.class, adapter::getDatabases,
                "A 500 from /databases should throw AdapterException carrying the HTTP status and message");
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), ex.getHttpCode());
        assertTrue(ex.getErrorMessage().contains("Internal adapter error"));
    }

    @Test
    void testDeleteBackup_404_treatedAsSuccess() {
        DbaasAdapter adapter = createWireMockAdapter();
        DatabasesBackup backup = new DatabasesBackup();
        backup.setLocalId("backup-404");
        backup.setAdapterId(POSTGRES_ADAPTER_ID);

        WireMockResource.getServer().stubFor(
                delete(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup-404"))
                        .willReturn(aResponse().withStatus(NOT_FOUND.getStatusCode()))
        );

        DeleteResult result = adapter.delete(backup);

        assertEquals(Status.SUCCESS, result.getStatus(),
                "A 404 on DELETE /backups/{id} should be treated as success");
    }

    @Test
    void testDeleteBackup_500_returnsFailWithAdapterErrorInMessage() {
        DbaasAdapter adapter = createWireMockAdapter();
        DatabasesBackup backup = new DatabasesBackup();
        backup.setLocalId("backup-500");
        backup.setAdapterId(POSTGRES_ADAPTER_ID);

        WireMockResource.getServer().stubFor(
                delete(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup-500"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Storage I/O failure"
                                        }
                                        """))
        );

        DeleteResult result = adapter.delete(backup);

        assertEquals(Status.FAIL, result.getStatus(),
                "A 500 from DELETE /backups/{id} must produce a FAIL result, not throw");
        assertTrue(result.getMessage().contains(String.valueOf(INTERNAL_SERVER_ERROR.getStatusCode())),
                "The FAIL message must include the HTTP status code returned by the adapter");
    }

    @Test
    void testUpdateSettings_500_rethrowsAdapterException() {
        DbaasAdapter adapter = createWireMockAdapter();
        String dbName = "settings-test-db";

        WireMockResource.getServer().stubFor(
                put(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases/" + dbName + "/settings"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Settings update rejected by adapter"
                                        }
                                        """))
        );

        AdapterException ex = assertThrows(AdapterException.class,
                () -> adapter.updateSettings(dbName, Map.of(), Map.of("maxConnections", 100)),
                "A 500 from PUT .../settings must re-throw AdapterException, not swallow it");
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), ex.getHttpCode());
        assertTrue(ex.getErrorMessage().contains("Settings update rejected by adapter"));
    }

    @Test
    void testAdapterSupports_500_rethrowsAdapterException() {
        DbaasAdapter adapter = createWireMockAdapter();

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Internal error in adapter"
                                        }
                                        """))
        );

        AdapterException ex = assertThrows(AdapterException.class, adapter::isUsersSupported,
                "A non-404 error on GET /supports must be re-thrown, not silently return defaults");
        assertEquals(INTERNAL_SERVER_ERROR.getStatusCode(), ex.getHttpCode());
    }

    @Test
    void testSecureClient_401_fallsBackToBasicAuth() {
        BasicAuthFilter basicAuthFilter = new BasicAuthFilter("user", "pass");
        KubernetesTokenAuthFilter tokenAuthFilter = new KubernetesTokenAuthFilter(() -> "fake-token");
        DynamicAuthFilter dynamicAuthFilter = new DynamicAuthFilter(tokenAuthFilter);

        DbaasAdapterRestClientV2 rawClient = RestClientBuilder.newBuilder()
                .baseUri(URI.create(wiremockAddress))
                .register(dynamicAuthFilter, Priorities.AUTHENTICATION)
                .register(new AdapterResponseExceptionMapper())
                .build(DbaasAdapterRestClientV2.class);
        SecureDbaasAdapterRestClientV2 secureClient = new SecureDbaasAdapterRestClientV2(
                rawClient, basicAuthFilter, tokenAuthFilter, dynamicAuthFilter, true);
        DbaasAdapter adapter = new DbaasAdapterRESTClientV2(
                wiremockAddress, PG_TYPE, secureClient, "secure-test", adapterActionTrackerClient);

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .inScenario("jwt-fallback")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(UNAUTHORIZED.getStatusCode()))
                        .willSetStateTo("after-401")
        );
        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .inScenario("jwt-fallback")
                        .whenScenarioStateIs("after-401")
                        .willReturn(aResponse()
                                .withStatus(OK.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"users\":true}"))
        );

        assertTrue(adapter.isUsersSupported(),
                "After a 401 with JWT auth the client should fall back to Basic auth and succeed");
    }

    @Test
    void testPasswordRotation_adapterError_landedInFailed() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(wiremockAdapter));

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .willReturn(aResponse()
                                .withStatus(OK.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"users\":true}"))
        );
        WireMockResource.getServer().stubFor(
                put(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/users/.*"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "User ensure failed in adapter"
                                        }
                                        """))
        );

        Database database = new DatabaseBuilder().registry().build();
        databaseRegistryDbaasRepository.saveInternalDatabase(database.getDatabaseRegistry().getFirst());

        PasswordChangeRequestV3 request = new PasswordChangeRequestV3();
        request.setType(PG_TYPE);

        PasswordChangeFailedException thrown = assertThrows(
                PasswordChangeFailedException.class,
                () -> passwordRotationService.changeUserPassword(request, TEST_NS),
                "When the adapter returns 500 for ensureUser the CP must land in the failed list, not propagate as AdapterException"
        );

        assertFalse(thrown.getResponse().getFailed().isEmpty(),
                "The failed list must contain the database whose adapter call returned 500");
    }

    @Test
    void testMigrationService_adapterGetDatabases_500_absorbedAsEmptyOptional() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        when(physicalDatabasesService.getAllAdapters()).thenReturn(List.of(wiremockAdapter));

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Databases fetch error"
                                        }
                                        """))
        );

        RegisterDatabaseRequestV3 request = new RegisterDatabaseRequestV3();
        request.setClassifier(new TreeMap<>(Map.of(
                "namespace", TEST_NS, "scope", "service", "microserviceName", TEST_MS
        )));
        request.setConnectionProperties(List.of(Map.of("username", "u", "password", "p")));
        request.setResources(new ArrayList<>());
        request.setNamespace(TEST_NS);
        request.setType(PG_TYPE);
        request.setName("migration-test-db");

        RegisterDatabaseResponseBuilder result = assertDoesNotThrow(
                () -> migrationService.registerDatabases(List.of(request), API_VERSION.V3, false),
                "An AdapterException from getDatabases() must be absorbed; registerDatabases() must not throw"
        );

        // The database cannot be resolved to an adapter (all returned Optional.empty()),
        // so it lands in the failed section of the response — not in migrated.
        assertNotEquals(OK.getStatusCode(), result.buildAndResponse().getStatus(),
                "The unresolvable database must produce a non-200 response, confirming it reached the failed path");
    }

    @Test
    void testStartBackup_4xxAdapterError_marksBackupFailed() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(wiremockAdapter);

        // 404 on /supports → backupRestore() defaults to true, so the database passes
        // isBackupRestoreSupported() checks inside backup() without throwing.
        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .willReturn(aResponse().withStatus(NOT_FOUND.getStatusCode()))
        );
        WireMockResource.getServer().stubFor(
                post(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup"))
                        .willReturn(aResponse()
                                .withStatus(422)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Validation failed"
                                        }
                                        """))
        );

        Database database = new DatabaseBuilder().registry().build();
        databaseRegistryDbaasRepository.saveInternalDatabase(database.getDatabaseRegistry().getFirst());

        Filter filter = new Filter();
        filter.setNamespace(List.of(TEST_NS));
        FilterCriteria criteria = new FilterCriteria();
        criteria.setInclude(List.of(filter));

        BackupRequest request = new BackupRequest();
        request.setBackupName("backup-4xx-test");
        request.setStorageName("test-storage");
        request.setBlobPath("test/blob/path");
        request.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        request.setFilterCriteria(criteria);
        request.setIgnoreNotBackupableDatabases(false);

        BackupResponse response = dbBackupV2Service.backup(request, false);

        assertEquals(BackupStatus.FAILED, response.getStatus(),
                "A 4xx from the adapter is a non-retryable error: the backup must be marked FAILED");
        assertTrue(response.getErrorMessage().contains("Validation failed"),
                "The error message must propagate from AdapterException.getErrorMessage() via RestClientExceptionUtil");
    }

    @Test
    void testStartBackup_5xxAdapterError_marksBackupRetryable() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(wiremockAdapter);

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .willReturn(aResponse().withStatus(NOT_FOUND.getStatusCode()))
        );
        WireMockResource.getServer().stubFor(
                post(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Internal Server Error"
                                        }
                                        """))
        );

        Database database = new DatabaseBuilder().registry().build();
        databaseRegistryDbaasRepository.saveInternalDatabase(database.getDatabaseRegistry().getFirst());

        Filter filter = new Filter();
        filter.setNamespace(List.of(TEST_NS));
        FilterCriteria criteria = new FilterCriteria();
        criteria.setInclude(List.of(filter));

        BackupRequest request = new BackupRequest();
        request.setBackupName("backup-5xx-test");
        request.setStorageName("test-storage");
        request.setBlobPath("test/blob/path");
        request.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        request.setFilterCriteria(criteria);
        request.setIgnoreNotBackupableDatabases(false);

        BackupResponse response = dbBackupV2Service.backup(request, false);

        // RETRYABLE_FAIL on the logical backup aggregates to IN_PROGRESS at the Backup level,
        // indicating the scheduler should pick it up for a retry.
        assertEquals(BackupStatus.IN_PROGRESS, response.getStatus(),
                "A 5xx from the adapter is a transient error: the backup must be marked IN_PROGRESS for retry");
        assertTrue(response.getErrorMessage().contains("Internal Server Error"),
                "The error message must propagate from AdapterException.getErrorMessage() via RestClientExceptionUtil");
    }

    @Test
    void testCreateOrUpdateDatabase_adapterSettingsError_propagatesErrorMessageInHttpResponse() {
        Database database = new DatabaseBuilder().registry().build();
        databaseRegistryDbaasRepository.saveInternalDatabase(database.getDatabaseRegistry().getFirst());

        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(wiremockAdapter);

        WireMockResource.getServer().stubFor(
                put(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases/.*/settings"))
                        .willReturn(aResponse()
                                .withStatus(422)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Settings rejected by adapter"
                                        }
                                        """))
        );

        DatabaseCreateRequestV3 request = new DatabaseCreateRequestV3();
        request.setClassifier(database.getDatabaseRegistry().getFirst().getClassifier());
        request.setType(PG_TYPE);
        request.setOriginService(TEST_MS);
        request.setSettings(Map.of("max-connections", 100));

        given()
                .auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .when()
                .put("/api/v3/dbaas/" + TEST_NS + "/databases")
                .then()
                .statusCode(422)
                .body(containsString("Settings rejected by adapter"));
    }

    @Test
    void testCreateDatabase_adapterProblem_propagateError() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        ExternalAdapterRegistrationEntry adapterEntry = new ExternalAdapterRegistrationEntry(
                POSTGRES_ADAPTER_ID, wiremockAddress, null, null, null);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier(POSTGRES_PHY_DB_ID);
        physicalDatabase.setAdapter(adapterEntry);
        when(balancingRulesService.applyBalancingRules(eq(PG_TYPE), eq(TEST_NS), any())).thenReturn(physicalDatabase);
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(wiremockAdapter);

        DatabaseCreateRequestV3 request = new DatabaseCreateRequestV3();
        request.setClassifier(new TreeMap<>(Map.of(
                "scope", "service",
                "namespace", TEST_NS,
                "microserviceName", "failed-test-ms"
        )));
        request.setType(PG_TYPE);
        request.setOriginService("failed-test-ms");

        WireMockResource.getServer().stubFor(
                post(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases"))
                        .willReturn(aResponse().withFixedDelay(3000))
        );
        given()
                .auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .when()
                .put("/api/v3/dbaas/" + TEST_NS + "/databases")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", equalTo("CORE-DBAAS-4056"))
                .body(containsString("Read timed out"));

        WireMockResource.getServer().stubFor(
                post(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/databases"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        Some obscure adapter error
                                        """))
        );
        given()
                .auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .when()
                .put("/api/v3/dbaas/" + TEST_NS + "/databases")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("code", equalTo("CORE-DBAAS-4056"))
                .body(containsString("Some obscure adapter error"));
    }

    @Test
    void testRestore_4xxAdapterError_immediatelyFailedWithNoRetries() {
        DbaasAdapter wiremockAdapter = createWireMockAdapter();
        ExternalAdapterRegistrationEntry adapterEntry = new ExternalAdapterRegistrationEntry(
                POSTGRES_ADAPTER_ID, wiremockAddress, null, null, null);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier(POSTGRES_PHY_DB_ID);
        physicalDatabase.setAdapter(adapterEntry);
        when(balancingRulesService.applyBalancingRules(eq(PG_TYPE), eq(TEST_NS), any())).thenReturn(physicalDatabase);
        when(physicalDatabasesService.getAdapterById(POSTGRES_ADAPTER_ID)).thenReturn(wiremockAdapter);

        WireMockResource.getServer().stubFor(
                get(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/supports"))
                        .willReturn(aResponse().withStatus(NOT_FOUND.getStatusCode()))
        );

        Backup backup = new Backup();
        backup.setName("restore-error-test-backup");
        backup.setStatus(BackupStatus.COMPLETED);
        backup.setStorageName("s3");
        backup.setBlobPath("/backups");
        backup.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);
        backup.setExternalDatabases(new ArrayList<>());

        LogicalBackup lb = new LogicalBackup();
        lb.setId(UUID.randomUUID());
        lb.setType(PG_TYPE);
        lb.setLogicalBackupName("test-logical-backup-001");
        lb.setBackup(backup);

        BackupDatabase bd = new BackupDatabase();
        bd.setId(UUID.randomUUID());
        bd.setLogicalBackup(lb);
        bd.setName("test-db");
        bd.setClassifiers(List.of(new TreeMap<>(Map.of(
                "namespace", TEST_NS, "microserviceName", TEST_MS, "scope", "service"))));
        bd.setUsers(List.of());

        lb.setBackupDatabases(List.of(bd));
        backup.setLogicalBackups(List.of(lb));
        backupRepository.save(backup);

        RestoreRequest request = new RestoreRequest();
        request.setRestoreName("restore-error-test");
        request.setStorageName("s3");
        request.setBlobPath("/backups");
        request.setExternalDatabaseStrategy(ExternalDatabaseStrategy.SKIP);

        // test for 4xx error - no retries expected
        WireMockResource.getServer().stubFor(
                post(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup/.*/restore"))
                        .willReturn(aResponse()
                                .withStatus(NOT_FOUND.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Backup not found in adapter storage"
                                        }
                                        """))
        );

        RestoreResponse response = dbBackupV2Service.restore(
                "restore-error-test-backup", request, false, true);

        assertEquals(RestoreStatus.FAILED, response.getStatus(),
                "A 4xx from the adapter during restore must immediately mark the restore as FAILED");
        WireMockResource.getServer().verify(
                1,
                postRequestedFor(urlPathMatching(
                        "/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup/.*/restore"))
        );

        // test for 5xx error - 3 retry for dryRun=true, 3 retry for dryRun=false, total 8 requests
        restoreRepository.listAll().forEach(restoreRepository::delete);
        WireMockResource.getServer().resetRequests();
        WireMockResource.getServer().stubFor(
                post(urlPathMatching("/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup/.*/restore"))
                        .willReturn(aResponse()
                                .withStatus(INTERNAL_SERVER_ERROR.getStatusCode())
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                                        {
                                          "message": "Unknown error during restore"
                                        }
                                        """))
        );

        RestoreResponse response2 = dbBackupV2Service.restore(
                "restore-error-test-backup", request, false, true);

        assertEquals(RestoreStatus.IN_PROGRESS, response2.getStatus(),
                "A 5xx from the adapter during restore must mark the restore as IN_PROGRESS");
        WireMockResource.getServer().verify(
                8,
                postRequestedFor(urlPathMatching(
                        "/api/v2/dbaas/adapter/" + PG_TYPE + "/backups/backup/.*/restore"))
        );
    }

    private DbaasAdapter createWireMockAdapter() {
        return factory.createDbaasAdapterClientV2(
                "u", "p",
                wiremockAddress,
                DatabaseType.POSTGRESQL.toString(),
                DatabaseBuilder.POSTGRES_ADAPTER_ID,
                adapterActionTrackerClient,
                new ApiVersion(List.of(new ApiVersion.Spec("", 2, 10, List.of(2))))
        );
    }
}
