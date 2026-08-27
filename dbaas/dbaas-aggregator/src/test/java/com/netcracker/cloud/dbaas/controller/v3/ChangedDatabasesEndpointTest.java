package com.netcracker.cloud.dbaas.controller.v3;

import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.PG_TYPE;
import static com.netcracker.cloud.dbaas.utils.DatabaseBuilder.TEST_NS;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        databaseRegistryRepository.delete("namespace = ?1", TEST_NS);
        databasesRepository.delete("namespace = ?1", TEST_NS);
    }

    @Test
    void seedReturnsHighWaterMarkAndNoItems() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        database.setLastRotatedAt(FUTURE);
        databasesRepository.persist(database);

        given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
                .when().get("/changed")
                .then()
                .statusCode(OK.getStatusCode())
                .body("items.size()", is(0))
                .body("highWaterMark.id", is(database.getDatabaseRegistry().getFirst().getId().toString()))
                .body("highWaterMark.lastRotatedAt", notNullValue());
    }

    @Test
    void changedReturnsRegistryAfterCursorViaDatabaseMarker() {
        Database database = new DatabaseBuilder()
                .registry()
                .build();
        database.setLastRotatedAt(FUTURE);
        databasesRepository.persist(database);

        given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
                .queryParam("sinceTs", SINCE_BEFORE_FUTURE)
                .queryParam("sinceId", ZERO_UUID)
                .when().get("/changed")
                .then()
                .statusCode(OK.getStatusCode())
                .body("items.size()", is(1))
                .body("items[0].id", is(database.getDatabaseRegistry().getFirst().getId().toString()))
                .body("items[0].type", is(PG_TYPE))
                .body("items[0].namespace", is(TEST_NS))
                .body("items[0].classifier.scope", is("service"))
                .body("items[0].lastRotatedAt", notNullValue());
    }

    @Test
    void keysetPagesThroughRegistriesSharingOneTimestamp() {
        // One database, two registries → both share the database's single last_rotated_at (the restore case).
        Database database = new DatabaseBuilder()
                .registry()
                .registry()
                .build();
        database.setLastRotatedAt(FUTURE);
        databasesRepository.persist(database);

        // Page 1: seed cursor (before FUTURE) returns the first registry by (lastRotatedAt, id).
        Response page1 = given().auth().preemptive().basic("cluster-operator", "someDefaultPassword")
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

        assertNotEquals(firstId, secondId,
                "keyset must advance past the first registry, not stall on the same timestamp");
        assertEquals(
                java.util.Set.of(database.getDatabaseRegistry().get(0).getId().toString(), database.getDatabaseRegistry().get(1).getId().toString()),
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
}
