package com.netcracker.cloud.dbaas.controller.v3;

import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Runtime contract test for GET /api/v3/dbaas/databases/changed against a real Postgres (no mocked repos),
 * so it also exercises the real findChangedSince / latestChange JPQL and the database.lastRotatedAt path.
 * Uses a far-future last_rotated_at so the test's rows are the unambiguous high-water mark and the only
 * rows after the cursor, independent of data left by other tests.
 */
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(AggregatedDatabaseAdministrationNoNamespaceControllerV3.class)
class ChangedDatabasesEndpointTest {

    private static final String NS = "changed-endpoint-test-ns";
    private static final String TYPE = "postgresql";
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";
    private static final OffsetDateTime FUTURE = OffsetDateTime.of(2999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String SINCE_BEFORE_FUTURE = FUTURE.minusSeconds(1).toString();

    @Inject
    DatabasesRepository databasesRepository;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        // Delete straight from Postgres (not via the dbaas abstraction, which may read a stale H2 cache),
        // so each test starts with only its own rows. Registries first, then their databases.
        databaseRegistryRepository.delete("namespace = ?1", NS);
        databasesRepository.delete("namespace = ?1", NS);
    }

    @Test
    void seedReturnsHighWaterMarkAndNoItems() {
        UUID regId = persistDatabase("seed-ms", FUTURE, 1).get(0);

        given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
                .when().get("/changed")
                .then()
                .statusCode(OK.getStatusCode())
                .body("items.size()", is(0))
                .body("highWaterMark.id", is(regId.toString()))
                .body("highWaterMark.lastRotatedAt", notNullValue());
    }

    @Test
    void changedReturnsRegistryAfterCursorViaDatabaseMarker() {
        UUID regId = persistDatabase("changed-ms", FUTURE, 1).get(0);

        given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
                .queryParam("sinceTs", SINCE_BEFORE_FUTURE)
                .queryParam("sinceId", ZERO_UUID)
                .when().get("/changed")
                .then()
                .statusCode(OK.getStatusCode())
                .body("items.size()", is(1))
                .body("items[0].id", is(regId.toString()))
                .body("items[0].type", is(TYPE))
                .body("items[0].namespace", is(NS))
                .body("items[0].classifier.scope", is("service"))
                .body("items[0].lastRotatedAt", notNullValue());
    }

    @Test
    void keysetPagesThroughRegistriesSharingOneTimestamp() {
        // One database, two registries → both share the database's single last_rotated_at (the restore case).
        List<UUID> ids = persistDatabase("ties-ms", FUTURE, 2);

        // Page 1: seed cursor (before FUTURE) returns the first registry by (lastRotatedAt, id).
        io.restassured.response.Response page1 = given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
                .queryParam("sinceTs", SINCE_BEFORE_FUTURE)
                .queryParam("sinceId", ZERO_UUID)
                .queryParam("limit", 1)
                .when().get("/changed")
                .then()
                .statusCode(OK.getStatusCode())
                .body("items.size()", is(1))
                .extract().response();
        String firstId = page1.path("items[0].id");
        String firstTs = page1.path("items[0].lastRotatedAt");

        // Page 2: advance the FULL keyset cursor — both lastRotatedAt and id, as the poller does — so the
        // equal-timestamp tie is broken by id and the OTHER registry is returned, not the same one.
        String secondId = given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
                .queryParam("sinceTs", firstTs)
                .queryParam("sinceId", firstId)
                .queryParam("limit", 1)
                .when().get("/changed")
                .then()
                .statusCode(OK.getStatusCode())
                .body("items.size()", is(1))
                .extract().path("items[0].id");

        org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId,
                "keyset must advance past the first registry, not stall on the same timestamp");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of(ids.get(0).toString(), ids.get(1).toString()),
                java.util.Set.of(firstId, secondId),
                "both registries sharing the timestamp must be paged through");
    }

    @Test
    void nonClusterOperatorIsForbidden() {
        given().auth().preemptive().basic("test_only_db_client", "someDefaultPassword")
                .when().get("/changed")
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
    }

    /**
     * Persists one Database (with last_rotated_at = rotatedAt) and the given number of registries pointing at
     * it; cascade persists the registries. Returns the registry ids.
     */
    private List<UUID> persistDatabase(String microservice, OffsetDateTime rotatedAt, int registries) {
        SortedMap<String, Object> baseClassifier = new TreeMap<>();
        baseClassifier.put("microserviceName", microservice);
        baseClassifier.put("scope", "service");
        baseClassifier.put("namespace", NS);

        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(baseClassifier);
        database.setType(TYPE);
        database.setNamespace(NS);
        database.setName("db-" + microservice);
        database.setAdapterId("adapter");
        database.setConnectionProperties(List.of(new HashMap<>(Map.of("username", "u", ROLE, "admin"))));
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setLastRotatedAt(rotatedAt);

        List<DatabaseRegistry> regs = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < registries; i++) {
            SortedMap<String, Object> classifier = new TreeMap<>(baseClassifier);
            if (registries > 1) {
                classifier.put("microserviceName", microservice + "-" + i); // distinct (classifier,type) per registry
            }
            DatabaseRegistry reg = new DatabaseRegistry();
            reg.setId(UUID.randomUUID());
            reg.setClassifier(classifier);
            reg.setType(TYPE);
            reg.setNamespace(NS);
            reg.setDatabase(database);
            regs.add(reg);
            ids.add(reg.getId());
        }
        database.setDatabaseRegistry(regs);
        databasesRepository.persist(database);
        return ids;
    }
}
