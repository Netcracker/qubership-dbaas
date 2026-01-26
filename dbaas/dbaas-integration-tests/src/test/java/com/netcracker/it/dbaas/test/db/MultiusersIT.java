package com.netcracker.it.dbaas.test.db;

import com.arangodb.ArangoCollection;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDBException;
import com.arangodb.ArangoDatabase;
import com.arangodb.entity.BaseDocument;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.UnauthorizedException;
import com.datastax.driver.core.querybuilder.Clause;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.PhysicalDatabaseRegistrationResponseDTOV3;
import com.netcracker.it.dbaas.entity.RegisteredPhysicalDatabasesDTOV3;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class MultiusersIT extends AbstractIT {

    protected final static RetryPolicy<Object> DB_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(10)).withMaxDuration(Duration.ofMinutes(3));

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    void postgresTestDatabaseCreatedAndConnecting() throws IOException {
        Assumptions.assumeTrue(isAdapterSupportMultiusersFeature(POSTGRES_TYPE), "Postgres adapter is not support multiusers feature. Skip test.");
        helperV3.createServicesRoles(POSTGRES_TYPE);
        log.info("Create database");
        DatabaseResponse databaseWithAdminConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), helperV3.getCreateDeclarativeDatabaseRequest(TEST_DECLARATIVE_MICROSERVICE_NAME, POSTGRES_TYPE, null), 201);
        log.info("Check connection to created database with admin role");
        checkConnectionPostgresForRole(databaseWithAdminConnection, "test", "test", "admin");
        log.info("Getting read-only connection properties");
        DatabaseResponse databaseWithRoConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), helperV3.getCreateDeclarativeDatabaseRequest("ro-service", POSTGRES_TYPE, "ro"), 200);
        log.info("Check connection properties for RO role");
        checkConnectionPostgresForRole(databaseWithRoConnection, "test", "test", "ro");
        log.info("Getting read-write connection properties");
        DatabaseResponse databaseWithRwConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), helperV3.getCreateDeclarativeDatabaseRequest("rw-service", POSTGRES_TYPE, "rw"), 200);
        log.info("Check connection properties for RW role");
        checkConnectionPostgresForRole(databaseWithRwConnection, "test", "test", "rw");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), helperV3.getCreateDeclarativeDatabaseRequest("ro-service", POSTGRES_TYPE, "rw"), 403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), helperV3.getCreateDeclarativeDatabaseRequest("rw-service", POSTGRES_TYPE, "admin"), 403);

    }

    @Test
    void mongoTestDatabaseCreatedAndConnecting() throws IOException {
        Assumptions.assumeTrue(isAdapterSupportMultiusersFeature(MONGODB_TYPE), "No adapter found with supported multiusers feature. Skip test.");
        helperV3.createServicesRoles(MONGODB_TYPE);
        log.info("Create database");
        DatabaseResponse databaseWithAdminConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest(TEST_DECLARATIVE_MICROSERVICE_NAME, MONGODB_TYPE, null),
                201);
        log.info("Check connection to created database with admin role");
        checkConnectionMongoForRole(databaseWithAdminConnection, false, "test", "test", "admin");
        log.info("Getting read-only connection properties");
        DatabaseResponse databaseWithRoConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", MONGODB_TYPE, "ro"),
                200);
        log.info("Check connection properties for RO role");
        checkConnectionMongoForRole(databaseWithRoConnection, false, "test", null, "ro");
        log.info("Getting read-write connection properties");
        DatabaseResponse databaseWithRwConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", MONGODB_TYPE, "rw"),
                200);
        log.info("Check connection properties for RW role");
        checkConnectionMongoForRole(databaseWithRwConnection, false, "test", "test", "rw");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", MONGODB_TYPE, "rw"),
                403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", MONGODB_TYPE, "admin"),
                403);

    }

    @Test
    void cassandraTestDatabaseCreatedAndConnecting() throws IOException {
        Assumptions.assumeTrue(isAdapterSupportMultiusersFeature(CASSANDRA_TYPE), "No adapter found with supported multiusers feature. Skip test.");
        helperV3.createServicesRoles(CASSANDRA_TYPE);
        log.info("Create database");
        DatabaseResponse databaseWithAdminConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest(TEST_DECLARATIVE_MICROSERVICE_NAME, CASSANDRA_TYPE, null),
                201);
        log.info("Check connection to created database with admin role");
        checkConnectionCassandra(databaseWithAdminConnection, "test", "test", "admin");
        log.info("Getting read-only connection properties");
        DatabaseResponse databaseWithRoConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", CASSANDRA_TYPE, "ro"),
                200);
        log.info("Check connection properties for RO role");
        checkConnectionCassandra(databaseWithRoConnection, null, "test", "ro");
        log.info("Getting read-write connection properties");
        DatabaseResponse databaseWithRwConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", CASSANDRA_TYPE, "rw"),
                200);
        log.info("Check connection properties for RW role");
        checkConnectionCassandra(databaseWithRwConnection, "test", "test", "rw");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", CASSANDRA_TYPE, "rw"),
                403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", CASSANDRA_TYPE, "admin"),
                403);

    }

    @Test
    void clickhouseTestDatabaseCreatedAndConnecting() throws IOException, SQLException {
        Assumptions.assumeTrue(isAdapterSupportMultiusersFeature(CLICKHOUSE_TYPE), "No adapter found with supported multiusers feature. Skip test.");
        helperV3.createServicesRoles(CLICKHOUSE_TYPE);
        log.info("Create database");
        DatabaseResponse databaseWithAdminConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest(TEST_DECLARATIVE_MICROSERVICE_NAME, CLICKHOUSE_TYPE, null),
                201);
        log.info("Check connection to created database with admin role");
        checkConnectionClickhouseForRole(databaseWithAdminConnection, "test", "test", "admin");
        log.info("Getting read-only connection properties");
        DatabaseResponse databaseWithRoConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", CLICKHOUSE_TYPE, "ro"),
                200);
        log.info("Check connection properties for RO role");
        checkConnectionClickhouseForRole(databaseWithRoConnection, null, "test", "ro");
        log.info("Getting read-write connection properties");
        DatabaseResponse databaseWithRwConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", CLICKHOUSE_TYPE, "rw"),
                200);
        log.info("Check connection properties for RW role");
        checkConnectionClickhouseForRole(databaseWithRwConnection, "test", "test", "rw");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", CLICKHOUSE_TYPE, "rw"),
                403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", CLICKHOUSE_TYPE, "admin"),
                403);
    }

    @Test
    void arangoTestDatabaseCreatedAndConnecting() throws IOException {
        Assumptions.assumeTrue(isAdapterSupportMultiusersFeature(ARANGODB_TYPE), "No adapter found with supported multiusers feature. Skip test.");
        helperV3.createServicesRoles(ARANGODB_TYPE);
        log.info("Create database");
        DatabaseResponse databaseWithAdminConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest(TEST_DECLARATIVE_MICROSERVICE_NAME, ARANGODB_TYPE, null),
                201);
        log.info("Check connection to created database with admin role");
        checkConnectionArangodbForRole(databaseWithAdminConnection, "test", "admin", "admin");
        log.info("Getting read-only connection properties");
        DatabaseResponse databaseWithRoConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", ARANGODB_TYPE, "ro"),
                200);
        log.info("Check connection properties for RO role");
        checkConnectionArangodbForRole(databaseWithRoConnection, "test", "admin", "ro");
        log.info("Getting read-write connection properties");
        DatabaseResponse databaseWithRwConnection = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", ARANGODB_TYPE, "rw"),
                200);
        log.info("Check connection properties for RW role");
        checkConnectionArangodbForRole(databaseWithRwConnection, "rw", "rw", "rw");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("ro-service", ARANGODB_TYPE, "rw"),
                403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                helperV3.getCreateDeclarativeDatabaseRequest("rw-service", ARANGODB_TYPE, "admin"),
                403);
    }

    public boolean isAdapterSupportMultiusersFeature(String dbType) {
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(helperV3.PHYSICAL_DATABASE_MAIN_PATH_V3, dbType))
                .addHeader("Authorization", "Basic " + helperV3.getClusterDbaAuthorization())
                .addHeader("X-Request-Id", getRequestId())
                .get()
                .build();
        Call call = okHttpClient.newCall(request);
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                log.info("There are no physical databases of type {}.", dbType);
                return false;
            }
            String body = response.body().string();
            log.info("Got physical databases of type {}: {}", dbType, body);
            RegisteredPhysicalDatabasesDTOV3 physicalDatabases = helperV3.objectMapper.readValue(body, RegisteredPhysicalDatabasesDTOV3.class);
            for (PhysicalDatabaseRegistrationResponseDTOV3 database : physicalDatabases.getIdentified().values()) {
                if (database.isGlobal() && helperV3.adapterIsAvailable(database.getAdapterAddress()) && database.getSupportedRoles().size() >= 3) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            log.debug("Error while getting adapter by db type {}:", dbType, e);
            return false;
        }
    }

    public void checkConnectionPostgresForRole(DatabaseResponse databaseToCheck, String setData, String checkData, String role) {
        try (Connection connection = helperV3.connectPg(databaseToCheck);
             Statement statement = connection.createStatement()) {
            Failsafe.with(DB_RETRY_POLICY).run(() -> assertTrue(statement.executeQuery("select 1;").next(), "Expect postgresql to return 1 row"));
            if (!role.equals("admin")) {
                Exception psqlEx = assertThrows(PSQLException.class, () ->
                        statement.execute("create table IF NOT EXISTS autotests( testdata varchar(200) NOT NULL);"));
                assertTrue(psqlEx.getMessage().contains("permission denied for schema public"));
                assertThrows(PSQLException.class, () -> statement.execute("CREATE USER tom WITH SUPERUSER;"));

            } else {
                Failsafe.with(DB_RETRY_POLICY).run(() -> statement.execute("create table IF NOT EXISTS autotests( testdata varchar(200) NOT NULL);"));
            }
            if (setData != null) {
                if (role.equals("ro"))
                    assertThrows(PSQLException.class, () -> statement.execute("insert into autotests (testdata) values( '" + setData + "');"));
                else {
                    Failsafe.with(DB_RETRY_POLICY).run(() -> {
                        statement.execute("insert into autotests (testdata) values( '" + setData + "');");
                        log.info("data {} saved in postgres  {}", setData, databaseToCheck);
                    });
                }
            }
            if (checkData != null) {
                Failsafe.with(DB_RETRY_POLICY).run(() -> {
                    assertTrue(
                            statement.executeQuery("select testdata from autotests where testdata='" + checkData + "';").next(), "Expect data to be returned");
                    log.info("data {} checked in postgres  {}", checkData, databaseToCheck);
                });
            }
        } catch (Exception v) {
            throw new CannotConnect(v);
        }
    }

    public void checkConnectionMongoForRole(DatabaseResponse databaseToCheck, Boolean expectFail, String setData, String checkData, String role) {
        String url = databaseToCheck.getConnectionPropertyAsString("url");
        assertNotNull(url);
        String database = Optional.ofNullable(databaseToCheck.getConnectionPropertyAsString("dbName"))
                .orElse(databaseToCheck.getConnectionPropertyAsString("authDbName"));
        try (MongoClient serviceMongoClient = helperV3.connectMongo(databaseToCheck, expectFail)) {

            if (role.equals("ro")) assertThrows(MongoException.class,
                    () -> serviceMongoClient.getDatabase(database).getCollection("autotestsData")
                            .insertOne(new Document("testkey", "testvalue")));
            else {
                Failsafe.with(DB_RETRY_POLICY).run(() -> {
                    serviceMongoClient.getDatabase(database).getCollection("autotestsData")
                            .insertOne(new Document("testkey", "testvalue"));
                    assertEquals(
                            "testvalue",
                            serviceMongoClient.getDatabase(database).getCollection("autotestsData")
                                    .find(new Document("testkey", "testvalue"))
                                    .first().get("testkey"));
                });
            }

            if (setData != null && !role.equals("ro")) {
                String value = setData + "_value";
                String key = setData + "_key";
                Failsafe.with(DB_RETRY_POLICY).run(() -> serviceMongoClient.getDatabase(database).getCollection("autotestsCheckingData")
                        .insertOne(new Document(key, value)));
                log.info("data {} saved in mongodb  {}", setData, databaseToCheck);
            }
            if (checkData != null) {
                String value = checkData + "_value";
                String key = checkData + "_key";
                Failsafe.with(DB_RETRY_POLICY).run(() -> {
                    assertEquals(
                            value,
                            serviceMongoClient.getDatabase(database).getCollection("autotestsCheckingData")
                                    .find(new Document(key, value))
                                    .first().get(key)
                    );
                    log.info("data {} checked in mongodb db {} ", checkData, databaseToCheck);
                });
            }
            if (!role.equals("admin")) {
                final BasicDBObject createUserCommand = new BasicDBObject("createUser", "myuser").append("pwd", "mypassword").append("roles",
                        Collections.singletonList(new BasicDBObject("role", "dbOwner").append("db", database)));
                assertThrows(MongoException.class, () -> serviceMongoClient.getDatabase(database).runCommand(createUserCommand));
            }
        } catch (Exception v) {
            throw new CannotConnect(v);
        }
    }


    public void checkConnectionCassandra(DatabaseResponse databaseToCheck, String setData, String checkData, String role) {
        log.info("Check cassandra connection");
        URL url = helperV3.createPortForward(databaseToCheck.getConnectionPropertyAsString("url"));
        Session session;
        String keyspace = databaseToCheck.getConnectionPropertyAsString("keyspace");
        try (
                Cluster cluster = Cluster.builder()
                        .withProtocolVersion(ProtocolVersion.V4)
                        .withoutMetrics()
                        .addContactPoint(url.getHost())
                        .withPort(url.getPort())
                        .withCredentials(databaseToCheck.getConnectionPropertyAsString("username"), databaseToCheck.getConnectionPropertyAsString("password"))
                        .build()
        ) {
            try {
                session = cluster.connect(keyspace);
                assertNotNull(session);
            } catch (Throwable e) {
                throw new CannotConnect(e);
            }
            Session finalSession = session;
            StringBuilder createQuery = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append("autotests").append("(")
                    .append("key text PRIMARY KEY, ")
                    .append("value text);");
            if (role.equals("admin")) {
                Failsafe.with(DB_RETRY_POLICY).run(() -> finalSession.execute(createQuery.toString()));
            } else {
                assertThrows(UnauthorizedException.class, () -> finalSession.execute(createQuery.toString()));
            }
            if (setData != null) {
                Insert insert = QueryBuilder.insertInto(keyspace, "autotests");
                insert.value("key", setData + "_key");
                insert.value("value", setData + "_value");
                if (role.equals("ro")) {
                    assertThrows(UnauthorizedException.class, () -> finalSession.execute(insert));
                } else {
                    Failsafe.with(DB_RETRY_POLICY).run(() -> finalSession.execute(insert));
                }
            }
            if (checkData != null) {
                Clause clause = QueryBuilder.eq("key", checkData + "_key");
                Select.Where select = QueryBuilder.select().from(keyspace, "autotests").where(clause);
                Failsafe.with(DB_RETRY_POLICY).run(() -> {
                    assertEquals(checkData + "_value", finalSession.execute(select).one().getString("value"));
                    log.info("data {} checked in cassandra db {} ", checkData, databaseToCheck);
                });
            }
            session.close();
        }
    }

    public void checkConnectionClickhouseForRole(DatabaseResponse databaseToCheck, String setData, String checkData, String role) throws SQLException {

        try (
                Connection conn = helperV3.connectClickhouse(databaseToCheck);
                Statement stmt = conn.createStatement()
        ) {
            String tableName = "autotests";
            StringBuilder createQuery = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append(tableName).append("(")
                    .append("key String, ")
                    .append("value String)")
                    .append("Engine = Memory;");
            if (role.equals("admin")) {
                Failsafe.with(DB_RETRY_POLICY).run(() -> stmt.execute(createQuery.toString()));
            } else {
                assertThrows(SQLException.class, () -> stmt.execute(createQuery.toString()));
            }
            if (setData != null) {
                String key = setData + "_key";
                String value = setData + "_value";
                String insert = String.format("INSERT INTO autotests (*) values ('%s', '%s')", key, value);
                if (role.equals("ro")) {
                    assertThrows(SQLException.class, () -> stmt.execute(insert));
                } else {
                    Failsafe.with(DB_RETRY_POLICY).run(() -> stmt.execute(insert));
                }
            }
            if (checkData != null) {
                Failsafe.with(DB_RETRY_POLICY).run(() -> {
                    ResultSet rs = stmt.executeQuery("select * from autotests");
                    while (rs.next()) {
                        assertEquals(checkData + "_key", rs.getString("key"));
                        assertEquals(checkData + "_value", rs.getString("value"));
                    }
                });
            }
        }
    }

    public void checkConnectionArangodbForRole(DatabaseResponse databaseToCheck, String setData, String val, String role) {

        ArangoDB arangoDb = helperV3.connectArangodb(databaseToCheck);
        ArangoDatabase arangoDatabase = arangoDb.db(databaseToCheck.getConnectionPropertyAsString("dbName"));
        String collectionName = "autotests";
        ArangoCollection collection = arangoDatabase.collection(collectionName);

        if (role.equals("admin")) {
            collection.create();
        } else {
            assertThrows(ArangoDBException.class, collection::create);
        }

        String key = setData + "_key";
        BaseDocument doc = new BaseDocument(key);
        doc.addAttribute(key, val);

        if (role.equals("ro")) {
            assertThrows(ArangoDBException.class, () -> collection.insertDocument(doc));
        } else {
            collection.insertDocument(doc);
        }

        BaseDocument document = collection.getDocument(key, BaseDocument.class);
        assertEquals(val, document.getAttribute(key));
    }

}
