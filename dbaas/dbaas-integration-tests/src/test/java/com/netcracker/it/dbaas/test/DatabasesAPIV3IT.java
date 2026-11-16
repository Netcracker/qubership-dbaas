package com.netcracker.it.dbaas.test;

import com.google.common.collect.ImmutableMap;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.response.SuccessfulRestoreUsersResponse;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class DatabasesAPIV3IT extends AbstractIT {

    private static String kubernetesServiceAccountToken;

    @BeforeAll
    static void setupAll() throws IOException {
        kubernetesServiceAccountToken = getKubernetesServiceAccountToken();
        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces();
        kubernetesClient.serviceAccounts()
                .withName(TEST_SERVICE_ACCOUNT_NAME)
                .delete();
    }

    @Override
    protected void closePortForwardAfterTest() {
    }

    @Nested
    @EnableExtension
    @Execution(ExecutionMode.CONCURRENT)
    class Database {

        @Nested
        @EnableExtension
        class DBaaS {

            @Test
            void testDeleteAllNotExistingDbs() throws IOException {
                var namespace = helperV3.generateTestNamespace();
                log.info("Delete databases from an empty namespace via the databases V3 endpoint");
                String responseBody = helperV3.deleteDatabases(DATABASES_V3, helperV3.getClusterDbaAuthorization(), namespace,
                        HttpStatus.SC_OK);
                assertEquals("namespace " + namespace + " doesn't contain any databases and namespace specific resources", responseBody);
            }

            @Test
            void testDeleteAllV3NotExistingDbs() throws IOException {
                var namespace = helperV3.generateTestNamespace();
                log.info("Delete databases from an empty namespace via the deleteall V3 endpoint");
                String responseBody = helperV3.deleteDatabases(DATABASES_DELETE_V3, helperV3.getClusterDbaAuthorization(), namespace,
                        HttpStatus.SC_OK);
                assertEquals("namespace " + namespace + " doesn't contain any databases and namespace specific resources", responseBody);
            }

            @Test
            @Tag("Smoke")
            public void saveExternalDatabase() {
                var namespace = helperV3.generateTestNamespace();
                ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", namespace);
                log.info("Save an external tarantool database");
                ExternalDatabaseResponseV3 externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 201, namespace);
                log.info("Check the type and the password of the saved external database");
                assertNotNull(externalDatabaseResponse.getId());
                assertTrue(externalDatabaseResponse.isExternallyManageable());
                String type = externalDatabaseResponse.getType();
                assertEquals("tarantool", type);
                Object password = externalDatabaseResponse.getConnectionProperties().getFirst().get("password");
                assertEquals("password", password);
                log.info("Save the same external database again and expect an idempotent response");
                helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 200, namespace);
            }

            @Test
            public void updateExternalDatabaseConnectionProperties() {
                var namespace = helperV3.generateTestNamespace();
                Map<String, Object> firstConnectionProperty = new HashMap<>();
                firstConnectionProperty.put("role", Role.ADMIN.getRoleValue());
                firstConnectionProperty.put("username", "external-user-test");
                firstConnectionProperty.put("password", "password");
                List<Map<String, Object>> connectionProperties = new ArrayList<>();
                connectionProperties.add(firstConnectionProperty);
                ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", namespace);
                log.info("Create an external tarantool database with one connection property");
                ExternalDatabaseResponseV3 externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, connectionProperties, "tarantool", false, 201, namespace);
                Assertions.assertEquals(connectionProperties, externalDatabaseResponse.getConnectionProperties());

                Map<String, Object> newConnectionProperty = new HashMap<>();
                newConnectionProperty.put("role", Role.ADMIN.getRoleValue());
                newConnectionProperty.put("username", "external-user-test2");
                newConnectionProperty.put("password", "password2");
                connectionProperties.add(newConnectionProperty);
                log.info("Update the connection properties with a second entry");
                externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, connectionProperties, "tarantool", true, 201, namespace);
                Assertions.assertEquals(connectionProperties, externalDatabaseResponse.getConnectionProperties());
                assertEquals(connectionProperties.get(1).get("password"), externalDatabaseResponse.getConnectionProperties().get(1).get("password"));

                log.info("Save the database with an empty list without the update flag and expect the properties to stay");
                externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, new ArrayList<>(), "tarantool", false, 200, namespace);
                Assertions.assertEquals(connectionProperties, externalDatabaseResponse.getConnectionProperties());

                log.info("Reject the connection properties update with an empty list");
                helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, new ArrayList<>(), "tarantool", true, 400, namespace);
            }

            @Test
            @Tag("Smoke")
            public void getExternalDatabasesByClassifier() throws IOException {
                var namespace = helperV3.generateTestNamespace();
                ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", namespace);
                log.info("Save an external tarantool database for the classifier lookup");
                helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 201, namespace);
                log.info("Get the external database by classifier and check that it has no id");
                DatabaseResponse externalDatabase = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, namespace, "tarantool", 200);
                assertNull(externalDatabase.getId());
                assertTrue(externalDatabase.isExternallyManageable());
            }
        }

        @Tag("postgresql")
        @Nested
        @EnableExtension
        class Postgresql extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return POSTGRES_TYPE;
            }

            @Test
            void testInitialScript_LO() throws IOException {
                log.info("Create a postgres database with the CREATE_LO_EXTENSION_IF_NOT_EXISTS initial script");
                helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "check InitialScript_LO", 201, POSTGRES_TYPE,
                        Collections.singletonList("CREATE_LO_EXTENSION_IF_NOT_EXISTS"), namespace, false, null, null, null);
            }

            @Test
            void testSettingsUpdateCRUDScenarios() throws IOException {
                String authorization = helperV3.getClusterDbaAuthorization();
                log.info("Create a postgres database without settings for the settings CRUD scenarios");
                DatabaseResponse initialDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, namespace, false);
                assertNull(initialDatabase.getSettings());

                //create : oldExtensions=[uuid-ossp], newExtensions=[bloom, uuid-ossp, seg]
                log.info("Create the settings with the bloom, uuid-ossp and seg extensions");
                Map<String, Object> settingsMap = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "seg"));
                DatabaseResponse updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                assertEquals(settingsMap, updatedDatabase.getSettings());
                assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

                //read : oldExtensions=[bloom, uuid-ossp, seg], newExtensions=[bloom, uuid-ossp, seg]
                log.info("Read the settings back without changes");
                updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                assertEquals(settingsMap, updatedDatabase.getSettings());
                assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

                //update (add new extension):  oldExtensions=[bloom, uuid-ossp, seg], newExtensions=[bloom, uuid-ossp, plpqsql], actual=[bloom, uuid-ossp, seg, plpgsql]
                log.info("Add the plpgsql extension to the settings");
                settingsMap = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "plpgsql"));
                updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                Map<String, Object> expectedSettings = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "seg", "plpgsql"));
                assertEquals(expectedSettings, updatedDatabase.getSettings());
                assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

                //update (add new extension):  [bloom, uuid-ossp, seg, plpgsql], newExtensions=[plpqsql], actual=[bloom, uuid-ossp, seg, plpgsql]
                log.info("Update the settings with the already present plpgsql extension");
                settingsMap = Collections.singletonMap("pgExtensions", List.of("plpgsql"));
                updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                expectedSettings = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "seg", "plpgsql"));
                assertEquals(expectedSettings, updatedDatabase.getSettings());
                assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

                //delete : oldExtensions=[bloom, uuid-ossp, seg, plpgsql], dropExtensions=[bloom, uuid-ossp, seg], actual=[plpqsql]
                log.info("Drop the bloom, uuid-ossp and seg extensions from the settings");
                settingsMap = Map.of("pgExtensions", Collections.singletonList("plpgsql"), "dropExtensions", List.of("bloom", "uuid-ossp", "seg"));
                updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                expectedSettings = Collections.singletonMap("pgExtensions", List.of("plpgsql"));
                assertEquals(expectedSettings, updatedDatabase.getSettings());
                assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());
            }

            @Test
            void testUpdateSettingsExtremeCases() throws IOException {
                String authorization = helperV3.getClusterDbaAuthorization();
                Map<String, Object> settingsMap = Collections.singletonMap("pgExtensions", Collections.singletonList("uuid-ossp"));
                log.info("Create a postgres database with the uuid-ossp extension for the extreme settings cases");
                DatabaseResponse databaseWithSettings = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, namespace, false, settingsMap);
                assertEquals(settingsMap, databaseWithSettings.getSettings());

                // null settings
                log.info("Update the database with null settings");
                DatabaseResponse updatedDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", null);
                assertEquals(databaseWithSettings.getSettings(), updatedDatabase.getSettings());
                assertEquals(databaseWithSettings.getClassifier(), updatedDatabase.getClassifier());

                // empty settings
                log.info("Update the database with empty settings");
                settingsMap = Collections.emptyMap();
                updatedDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                assertEquals(databaseWithSettings.getSettings(), updatedDatabase.getSettings());
                assertEquals(databaseWithSettings.getClassifier(), updatedDatabase.getClassifier());

                // empty extensions
                log.info("Drop the uuid-ossp extension by the dropExtensions settings");
                settingsMap = Collections.singletonMap("dropExtensions", List.of("uuid-ossp"));
                updatedDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, namespace, false, null, "dbaas_autotests", settingsMap);
                assertEquals(Collections.singletonMap("pgExtensions", Collections.EMPTY_LIST), updatedDatabase.getSettings());
                assertEquals(databaseWithSettings.getClassifier(), updatedDatabase.getClassifier());
            }

        }

        @Tag("mongodb")
        @Nested
        @EnableExtension
        class Mongodb extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return MONGODB_TYPE;
            }

        }

        @Tag("opensearch")
        @Nested
        @EnableExtension
        class Opensearch extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return OPENSEARCH_TYPE;
            }

            @Test
            @Tag("postgresql")
            void testDeleteAllDatabasesInNamespaceAsync_ButOpensearchSync() throws Exception {
                String clusterDbaAuthorization = helperV3.getClusterDbaAuthorization();
                Map<String, Object> settingsMap = Collections.singletonMap("resourcePrefix", true);
                log.info("Create two postgresql and one opensearch database in the namespace");
                helperV3.createDatabase(clusterDbaAuthorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, namespace, false, null);
                helperV3.createDatabase(clusterDbaAuthorization, "dbaas_auto_test_2", 201, POSTGRES_TYPE, null, namespace, false, null);
                helperV3.createDatabase(clusterDbaAuthorization, "opensearch1", 201, OPENSEARCH_TYPE, null, namespace, false, null, "test-prefix", settingsMap);
                log.info("Try to create a duplicate opensearch database and expect a server error");
                helperV3.createDatabase(clusterDbaAuthorization, "opensearch2", 500, OPENSEARCH_TYPE, null, namespace, false, null, "test-prefix", settingsMap);
                log.info("Check that the namespace contains the three created databases");
                List<DatabaseV3> databasesInNamespace = helperV3.getDatabasesByNamespace(namespace);
                assertThat(databasesInNamespace, hasSize(3));

                log.info("Delete all databases in the namespace via the deleteall endpoint");
                helperV3.deleteDatabasesAsync(DATABASES_DELETE_V3, clusterDbaAuthorization, namespace, HttpStatus.SC_OK);

                log.info("Recreate the opensearch database and wait until it is the only one left in the namespace");
                DatabaseResponse ops2 = helperV3.createDatabase(clusterDbaAuthorization, "opensearch2", 201, OPENSEARCH_TYPE, null, namespace, false, null, "test-prefix", settingsMap);
                Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(2)).withDelay(Duration.ofSeconds(1))).run(() -> {
                    List<DatabaseV3> databases = helperV3.getDatabasesByNamespace(namespace);
                    assertEquals(1, databases.size());
                    assertEquals(ops2.getId(), databases.getFirst().getId());
                });
            }
        }

        @Tag("arangodb")
        @Nested
        @EnableExtension
        class Arangodb extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return ARANGODB_TYPE;
            }
        }

        @Tag("cassandra")
        @Nested
        @EnableExtension
        class Cassandra extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return CASSANDRA_TYPE;
            }
        }

        @Tag("clickhouse")
        @Nested
        @EnableExtension
        class Clickhouse extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return CLICKHOUSE_TYPE;
            }
        }

        @Tag("redis")
        @Nested
        @EnableExtension
        class Redis extends AbstractDatabaseApiTest {

            @Override
            protected String databaseType() {
                return REDIS_TYPE;
            }
        }

    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract static class AbstractDatabaseApiTest {
        protected abstract String databaseType();

        protected String namespace;
        protected final String databaseType = databaseType();


        private String skipMessage() {
            return MessageFormat.format("No {0} adapter. Skip test.", databaseType);
        }

        private String multiUsersSkipMessage() {
            return MessageFormat.format("No {0} adapter with the multiusers feature. Skip test.", databaseType);
        }

        @BeforeAll
        void beforeAll() {
            DbaasHelperV3.regenerateRequestId();
            assumeTrue(helperV3.hasAdapterOfType(databaseType), skipMessage());
        }

        @BeforeEach
        void prepareNamespace() {
            namespace = helperV3.generateTestNamespace();
        }

        @Test
        @Tag("Smoke")
        void testDatabaseCreatedAndConnecting() throws IOException {
            log.info("Create a {} database in a fresh test namespace", databaseType);
            DatabaseResponse created_1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            assertThat(created_1, inNamespace(namespace));
            assertFalse(created_1.isExternallyManageable());
            log.info("Check the connection to the created {} database", databaseType);
            helperV3.checkConnection(false, created_1, "test_data", "test_data", false);
        }

        @Test
        void testDBCreationWithSettings() throws IOException {
            Map<String, Object> settingsMap = Collections.singletonMap("autotestsSettings", true);
            log.info("Create a {} database with the autotests settings", databaseType);
            DatabaseResponse databaseWithSettings = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false, settingsMap);
            log.info("Check that the created {} database echoes back the requested settings", databaseType);
            assertEquals(helperV3.dynamicSettings(databaseType, settingsMap), databaseWithSettings.getSettings());
        }

        @Test
        @Tag("Smoke")
        void testUserCreatedAndConnecting() throws IOException {
            String logicalUserId = "test-service";
            assumeTrue(helperV3.isAdapterSupportMultiusersFeature(databaseType), multiUsersSkipMessage());
            log.info("Create a {} database for the user creation test", databaseType);
            DatabaseResponse created_1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            log.info("Check the connection to the {} database created for the user test", databaseType);
            helperV3.checkConnection(false, created_1, null, null, false);

            Map<String, Object> classifier = new ClassifierBuilder().test("dbaas_auto_test_1").ns(namespace).build();
            GetOrCreateUserRequest getOrCreateUserRequest = new GetOrCreateUserRequest(classifier, logicalUserId,
                    databaseType, null, "test", Role.ADMIN.getRoleValue());
            log.info("Create an admin user in the {} database created for the user test", databaseType);
            GetOrCreateUserResponse user = helperV3.getOrCreateUser(USER_API_URL_V3, getOrCreateUserRequest, 201);
            log.info("Check the connection with the user created for the user test");
            helperV3.checkConnectionUser(false, databaseType, user, created_1, "test", "test");

            log.info("Rotate the password of the user created for the user test");
            UserOperationRequest userOperationRequest = new UserOperationRequest();
            userOperationRequest.setUserId(user.getUserId());
            Map<String, Object> newConnectionProperties = helperV3.rotateUserPassword(USER_API_URL_V3 + "/rotate-password",
                            userOperationRequest,
                            200)
                    .getConnectionProperties();
            assertNotEquals(user.getConnectionPropertyAsString("password"), newConnectionProperties.get("password"));

            log.info("Check the connection with the rotated password");
            GetOrCreateUserResponse userWithNewPassword = new GetOrCreateUserResponse();
            userWithNewPassword.setConnectionProperties(newConnectionProperties);
            helperV3.checkConnectionUser(false, databaseType, userWithNewPassword, created_1, "test", "test");

            log.info("Delete the user created for the user test");
            helperV3.deleteUser(USER_API_URL_V3, userOperationRequest, 200);
            log.info("Check that the connection with the deleted user is no longer possible");
            helperV3.checkConnectionUser(true, databaseType, userWithNewPassword, created_1, null, null);
        }

        @Test
        void testGetAllDatabasesInNamespaceWithCreatedUsers() throws IOException {
            String logicalUserId = "test-service";
            assumeTrue(helperV3.isAdapterSupportMultiusersFeature(databaseType), multiUsersSkipMessage());
            log.info("Create a {} database for the namespace listing test", databaseType);
            DatabaseResponse created_1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            log.info("Check the connection to the {} database created for the listing test", databaseType);
            helperV3.checkConnection(false, created_1, null, null, false);

            Map<String, Object> classifier = new ClassifierBuilder().test("dbaas_auto_test_1").ns(namespace).build();
            GetOrCreateUserRequest getOrCreateUserRequest = new GetOrCreateUserRequest(classifier, logicalUserId,
                    databaseType, null, "test", Role.ADMIN.getRoleValue());
            log.info("Create a user in the {} database created for the listing test", databaseType);
            GetOrCreateUserResponse user = helperV3.getOrCreateUser(USER_API_URL_V3, getOrCreateUserRequest, 201);

            log.info("Check the connection with the user created for the listing test");
            helperV3.checkConnectionUser(false, databaseType, user, created_1, "test", "test");

            log.info("List the namespace databases and check the user connection properties");
            Request request = helperV3.createRequest(String.format(DATABASES_LIST_V3, namespace),
                    helperV3.getClusterDbaAuthorization(),
                    null,
                    "GET");
            List<DatabaseV3> databases = Arrays.asList(helperV3.executeRequest(request, DatabaseV3[].class, 200));
            assertEquals(1, databases.size());
            Optional<Map<String, Object>> connections = databases.getFirst().getConnectionProperties().stream()
                    .filter(u -> u.get("logicalUserId") != null && u.get("logicalUserId").equals(logicalUserId))
                    .findFirst();
            assertTrue(connections.isPresent());
        }

        @Test
        void testUserDeletedAfterDatabaseDeletion() throws IOException {
            String logicalUserId = "test-service";
            assumeTrue(helperV3.isAdapterSupportMultiusersFeature(databaseType), multiUsersSkipMessage());
            log.info("Create a {} database for the user deletion on database deletion test", databaseType);
            DatabaseResponse created_1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            log.info("Check the connection to the {} database created for the user deletion test", databaseType);
            helperV3.checkConnection(false, created_1, null, null, false);

            Map<String, Object> classifier = new ClassifierBuilder().test("dbaas_auto_test_1").ns(namespace).build();
            GetOrCreateUserRequest getOrCreateUserRequest = new GetOrCreateUserRequest(classifier, logicalUserId,
                    databaseType, null, "test", Role.ADMIN.getRoleValue());
            log.info("Create a user in the {} database created for the user deletion test", databaseType);
            GetOrCreateUserResponse user = helperV3.getOrCreateUser(USER_API_URL_V3, getOrCreateUserRequest, 201);

            log.info("Check the connection with the user created for the user deletion test");
            helperV3.checkConnectionUser(false, databaseType, user, created_1, "test", "test");

            log.info("Delete all databases in the namespace of the user deletion test");
            helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), namespace);

            log.info("Check that the user password rotation fails after the database deletion");
            UserOperationRequest userOperationRequest = new UserOperationRequest();
            userOperationRequest.setUserId(user.getUserId());
            helperV3.rotateUserPasswordWithException(USER_API_URL_V3 + "/rotate-password",
                    userOperationRequest,
                    404);

            log.info("Check that the connection to the user fails after the database deletion");
            helperV3.checkConnectionUser(true, databaseType, user, created_1, null, null);

            log.info("Delete the user after the database deletion");
            helperV3.deleteUser(USER_API_URL_V3, userOperationRequest, 204);
        }

        @Test
        void testUsersRestore() throws IOException {
            assumeTrue(helperV3.isAdapterSupportMultiusersFeature(databaseType), multiUsersSkipMessage());
            log.info("Create a {} database for the user restore test", databaseType);
            DatabaseResponse created_1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            log.info("Check the connection to the {} database created for the user restore test", databaseType);
            helperV3.checkConnection(false, created_1, null, null, false);

            Map<String, Object> classifier = new ClassifierBuilder().test("dbaas_auto_test_1").ns(namespace).build();
            RestoreUsersRequest restoreUsersRequest = new RestoreUsersRequest(classifier, databaseType);
            log.info("Restore the users of the {} database created for the user restore test", databaseType);
            Request restoreUsers = helperV3.createRequest("api/v3/dbaas/users/restore", helperV3.getClusterDbaAuthorization(), restoreUsersRequest, "POST");
            log.debug("Restore users request: {}", restoreUsers);
            helperV3.executeRequest(restoreUsers, SuccessfulRestoreUsersResponse.class, 200);
            log.info("Check the connection to the restored users");

            helperV3.checkConnection(false, created_1, null, null, false);
        }

        @Test
        void testDatabaseCreatedAndConnectingWithK8sToken() throws IOException {
            log.info("Create database with k8s token");

            var req = DatabaseCreateRequestV3.builder(new ClassifierBuilder().test("dbaas_auto_test_1").ns(kubernetesClient.getNamespace()).build(), databaseType)
                    .originService(TEST_MICROSERVICE_NAME)
                    .build();
            DatabaseResponse created_1 = helperV3.createDatabaseWithK8sToken(kubernetesServiceAccountToken, String.format(DATABASES_V3, kubernetesClient.getNamespace()), req, 201);

            assertThat(created_1, inNamespace(kubernetesClient.getNamespace()));
            assertFalse(created_1.isExternallyManageable());
            log.info("Check connection to created with k8s token database");
            helperV3.checkConnection(false, created_1, null, null, false);

            helperV3.deleteDatabasesByClassifierRequestWithK8sToken(kubernetesServiceAccountToken, kubernetesClient.getNamespace(), databaseType, req.getClassifier(), 200);
        }

        @Test
        void testDatabaseCreatedWithNamePrefix() throws IOException {
            // Redis doesn't allow use underscore in database name
            assumeTrue(!REDIS_TYPE.equals(databaseType));
            String namePrefix = "dbaas_autotests";
            DatabaseResponse db = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false, null, namePrefix, null);
            helperV3.checkConnection(false, db, null, null, false);
            assertThat(db, inNamespace(namespace));
            assertTrue(db.getName().contains(namePrefix));
        }

        @Test
        @Tag("Smoke")
        void testDbsCleaned() throws IOException {
            log.info("Create a {} database for the cleaned databases check", databaseType);
            DatabaseResponse db = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            log.info("Check the connection to the {} database created for the cleaned check", databaseType);
            helperV3.checkConnection(false, db, null, null, false);
            assertThat(db, inNamespace(namespace));
            log.info("Delete all databases in the namespace of the cleaned check");
            helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), namespace);
            log.info("Check that the connection to the deleted {} database is no longer possible", databaseType);
            helperV3.checkConnection(true, db, null, null, true);
        }

        @Test
        void testAsyncDatabaseCreatedAndConnecting() throws IOException {
            String requestUrl = String.format(DATABASES_V3_ASYNC, namespace);
            log.info("Send the asynchronous create request for a {} database", databaseType);
            Request createDbRequest = helperV3.createDatabaseRequest(requestUrl, helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", databaseType, namespace);
            log.debug("Async create request: {}", createDbRequest);
            Response response = helperV3.executeRequest(createDbRequest, 202);
            response.close();

            log.info("Wait for the asynchronous creation of the {} database", databaseType);
            DatabaseResponse createdResponse = Failsafe.with(AWAIT_DB_CREATION_RETRY_POLICY).get(() ->
                    helperV3.createDatabase(requestUrl, helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 200,
                            databaseType, null, namespace, false, null, null, null));
            assertThat(createdResponse, inNamespace(namespace));
            log.info("Check the connection to the asynchronously created {} database", databaseType);
            helperV3.checkConnection(false, createdResponse, null, null, false);
        }

        @Test
        void testDeleteAllDatabasesInNamespace_OldApi() throws Exception {
            checkDatabaseDeletion(DATABASES_V3, "databases");
        }

        @Test
        void testDeleteAllDatabasesInNamespace_NewApi() throws Exception {
            checkDatabaseDeletion(DATABASES_DELETE_V3, "deleteall");
        }

        private void checkDatabaseDeletion(String url, String apiName) throws Exception {
            String clusterDbaAuthorization = helperV3.getClusterDbaAuthorization();
            log.info("Create a {} database for the delete-all check via the {} API", databaseType, apiName);
            helperV3.createDatabase(clusterDbaAuthorization, "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            List<DatabaseV3> databasesInNamespace = helperV3.getDatabasesByNamespace(namespace);
            assertThat(databasesInNamespace, hasSize(1));

            log.info("Delete all databases in the namespace via the {} API and check that the namespace is empty", apiName);
            helperV3.deleteDatabases(url, clusterDbaAuthorization, namespace, HttpStatus.SC_OK);
            databasesInNamespace = helperV3.getDatabasesByNamespace(namespace);
            assertThat(databasesInNamespace, hasSize(0));
        }

        @Test
        void testDuplicateNotCreated() throws IOException {
            String authorization = helperV3.getClusterDbaAuthorization();
            log.info("Create two {} databases in a fresh test namespace", databaseType);
            DatabaseResponse created_1 = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, databaseType, null, namespace, false);
            assertThat(created_1, inNamespace(namespace));
            log.info("Create the second {} database", databaseType);
            DatabaseResponse created_2 = helperV3.createDatabase(authorization, "dbaas_auto_test_2", 201, databaseType, null, namespace, false);
            assertThat(created_2, inNamespace(namespace));
            log.info("Repeat the creation of the second {} database and expect a 200 response", databaseType);
            DatabaseResponse duplicate_2 = helperV3.createDatabase(authorization, "dbaas_auto_test_2", 200, databaseType, null, namespace, false);
            assertThat(duplicate_2, inNamespace(namespace));
            log.info("Check that the repeated creation returns the same name without an id");
            assertEquals(created_2.getName(), duplicate_2.getName());
            assertNull(duplicate_2.getId());
        }

        @Test
        void testGetNotExistingDb() throws IOException {
            String authorization = helperV3.getClusterDbaAuthorization();
            Map<String, Object> classifierMap = new HashMap<>();
            classifierMap.put("dbClassifier", "default");
            classifierMap.put("scope", "service");
            classifierMap.put("microserviceName", "non-existing-service");
            classifierMap.put("namespace", namespace);

            log.info("Get a not existing {} database by classifier and expect a 404 response", databaseType);
            try (Response response = helperV3.getDatabaseByClassifierAsResponse(authorization, classifierMap, databaseType, namespace)) {
                assertThat(response.code(), is(404));
            }
        }

        @Test
        void testListDatabasesAnyType() throws IOException {
            ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external_any_type", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", namespace);
            log.info("Create an external and a {} database in a fresh test namespace", databaseType);
            helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 201, namespace);
            helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_any_type", 201, databaseType, null, namespace, false);
            List<DatabaseV3> databasesByNamespace = helperV3.getDatabasesByNamespace(namespace);
            List<DatabaseV3> databasesByExternalClassifier = filterByClassifierField(databasesByNamespace, "typeLogicalDb", "external_any_type");
            log.info("External databases in the namespace: {}", databasesByExternalClassifier);
            List<DatabaseV3> databasesByAutotestClassifier = filterByClassifierField(databasesByNamespace, "dbaas_auto_test", "dbaas_auto_test_any_type");
            log.info("Internal databases in the namespace: {}", databasesByAutotestClassifier);
            databasesByExternalClassifier.addAll(databasesByAutotestClassifier);
            List<DatabaseV3> notMarkedForDrop = filterByMarkNotForDrop(databasesByExternalClassifier);
            log.info("Databases not marked for the drop: {}", notMarkedForDrop);
            assertThat(notMarkedForDrop, hasSize(2));
            Map<String, List<DatabaseV3>> databaseMap = notMarkedForDrop.stream().collect(Collectors.groupingBy(DatabaseV3::getType));
            assertThat(databaseMap.get(databaseType), hasSize(1));
            assertThat(databaseMap.get("tarantool"), hasSize(1));
            assertFalse(databaseMap.get(databaseType).getFirst().isExternallyManageable());
            assertTrue(databaseMap.get("tarantool").getFirst().isExternallyManageable());
        }

        @Test
        void checkResponseWhenDatabaseIsNotCreatedYet() throws InterruptedException, ExecutionException {
            Integer[] expectedAnswers = {201, 202};

            log.info("Send three concurrent create requests for the same {} database", databaseType);
            String requestId = DbaasHelperV3.getRequestId();
            Callable<DatabaseResponse> requestToCreateDb = () -> {
                DbaasHelperV3.setRequestId(requestId);
                return helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", databaseType, null, namespace, false, expectedAnswers);
            };
            List<Callable<DatabaseResponse>> callableTasks = new ArrayList<>();
            callableTasks.add(requestToCreateDb);
            callableTasks.add(requestToCreateDb);
            callableTasks.add(requestToCreateDb);

            List<Future<DatabaseResponse>> receivedDatabases;
            try (ExecutorService es = Executors.newFixedThreadPool(3)) {
                receivedDatabases = es.invokeAll(callableTasks);
            }
            log.info("Check that all concurrent create requests received the connection properties");
            for (Future<DatabaseResponse> db : receivedDatabases) {
                DatabaseResponse currentDatabase = db.get();
                assertThat(currentDatabase.getConnectionProperties(), is(notNullValue()));
            }

        }

        @Test
        void testDatabaseCreatedAndConnectionContainsTLS() throws IOException, SQLException { // TODO generelize test
            assumeTrue(helperV3.hasAdapterOfType(POSTGRES_TYPE), MessageFormat.format("No {0} adapter. Skip test.", POSTGRES_TYPE));
            log.info("Create a postgres database for the TLS connection check");
            DatabaseResponse created_1 = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, namespace, false);
            assumeTrue(helperV3.isTLSEnabledInDatabase(POSTGRES_TYPE, created_1.getPhysicalDatabaseId()), "Skip test because TLS is disabled in database");
            log.info("Check the tls key in the connection properties and the connection to the database");
            Map<String, Object> connectionProperties = created_1.getConnectionProperties();
            assertTrue(connectionProperties.containsKey("tls"));
            helperV3.checkConnectionPostgres(created_1);
        }

        private List<DatabaseV3> filterByClassifierField(List<DatabaseV3> databases, String key, String value) {
            return databases.stream().filter(database -> database.getClassifier().containsKey(key) && database.getClassifier().get(key).equals(value))
                    .collect(Collectors.toList());
        }

        private List<DatabaseV3> filterByMarkNotForDrop(List<DatabaseV3> databases) {
            return databases.stream().filter(database -> !database.getClassifier().containsKey("MARKED_FOR_DROP")).collect(Collectors.toList());
        }

        @NotNull
        private BaseMatcher<DatabaseResponse> inNamespace(final String namespace) {
            return new BaseMatcher<>() {
                @Override
                public void describeTo(Description description) {
                    description.appendText("should have namespace " + namespace);
                }

                @Override
                public boolean matches(Object o) {
                    return ((DatabaseResponse) o).getNamespace().equals(namespace);
                }
            };
        }
    }

    private static String getKubernetesServiceAccountToken() {
        ServiceAccount sa = new ServiceAccountBuilder()
                .withNewMetadata()
                .withName(TEST_SERVICE_ACCOUNT_NAME)
                .endMetadata()
                .build();
        kubernetesClient.serviceAccounts().resource(sa).serverSideApply();
        kubernetesClient.serviceAccounts()
                .withName(TEST_SERVICE_ACCOUNT_NAME)
                .waitUntilCondition(Objects::nonNull, 30, TimeUnit.SECONDS);

        TokenRequest tokenRequest = new TokenRequestBuilder()
                .withNewSpec()
                .withAudiences("dbaas")
                .withExpirationSeconds(3600L)
                .endSpec()
                .build();
        TokenRequest result = kubernetesClient.serviceAccounts()
                .withName(TEST_SERVICE_ACCOUNT_NAME)
                .tokenRequest(tokenRequest);
        return result.getStatus().getToken();
    }
}
