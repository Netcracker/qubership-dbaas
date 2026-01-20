package com.netcracker.it.dbaas.helpers;

import com.arangodb.ArangoDB;
import com.arangodb.entity.BaseDocument;
import com.clickhouse.jdbc.ClickHouseDataSource;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.GsonBuilder;
import com.mongodb.*;
import com.netcracker.cloud.junit.cloudcore.extension.provider.LocalHostAddressGenerator;
import com.netcracker.cloud.junit.cloudcore.extension.service.Endpoint;
import com.netcracker.cloud.junit.cloudcore.extension.service.NetSocketAddress;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.cloud.junit.cloudcore.extension.service.ServicePortForwardParams;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.backup.v3.NamespaceBackupV3;
import com.netcracker.it.dbaas.entity.config.PolicyRole;
import com.netcracker.it.dbaas.entity.config.SecurityRuleConfigurationRequest;
import com.netcracker.it.dbaas.entity.config.ServiceRole;
import com.netcracker.it.dbaas.entity.response.AccessGrantsResponse;
import com.netcracker.it.dbaas.entity.response.GhostDatabasesResponse;
import com.netcracker.it.dbaas.entity.response.LostDatabasesResponse;
import com.netcracker.it.dbaas.entity.response.OverallStatusResponse;
import com.netcracker.it.dbaas.exceptions.CannotCheckData;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpStatus;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opentest4j.AssertionFailedError;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.netcracker.it.dbaas.test.AbstractIT.DEFAULT_RETRY_POLICY;
import static com.netcracker.it.dbaas.test.AbstractIT.OPENSEARCH_TYPE;
import static io.undertow.server.handlers.SSLHeaderHandler.HTTPS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class DbaasHelperV3 {
    public final static RetryPolicy<Object> AWAIT_DB_CREATION_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(5)).withMaxDuration(Duration.ofMinutes(2));

    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");
    public static final String TEST_NAMESPACE = "dbaas-autotests";
    public static final String TEST_MICROSERVICE_NAME = "dbaas-test-service";
    public static final String TEST_DECLARATIVE_MICROSERVICE_NAME = "dbaas-declarative-service";

    public static final String DATABASES_V3 = "api/v3/dbaas/%s/databases";
    public static final String DATABASES_V3_ASYNC = DATABASES_V3 + "?async=true";
    public static final String DATABASES_DELETE_V3 = DATABASES_V3 + "/deleteall";
    public static final String EXTERNALLY_MANAGEABLE_V3 = "api/v3/dbaas/%s/databases/registration/externally_manageable";
    private static final String CHANGE_PASSWORD_URL_V3 = "api/v3/dbaas/namespaces/%s/password-changes";
    public static final String TEST_CLASSIFIER_KEY = "dbaas_auto_test";

    private static final String BACKUPS_V3 = "api/v3/dbaas/backups";
    private static final String BACKUPS_DELETE_V3 = BACKUPS_V3 + "/deleteall";
    private static final String NAMESPACE_BACKUPS_V3 = "api/v3/dbaas/%s/backups";
    private static final String NAMESPACE_BACKUPS_DELETE_V3 = NAMESPACE_BACKUPS_V3 + "/deleteall";

    private static final String DEBUG_NAMESPACES_V3 = "api/v3/dbaas/debug/internal/namespaces";

    public final String PHYSICAL_DATABASE_MAIN_PATH_V3 = "api/v3/dbaas/%s/physical_databases";
    public static final String BASE_DECLARATIVE_SERVICE_URL = "api/dbaas/declarative";
    public static final String DECLARATIVE_SERVICE_URL = BASE_DECLARATIVE_SERVICE_URL + "/namespaces/%s/service/%s";

    public static final String CONFIGS_PATH_V1 = "api/declarations/v1";

    public static final String USER_API_URL_V3 = "api/v3/dbaas/users";
    public static final String PERMANENT_RULES_V3 = "/api/v3/dbaas/balancing/rules/permanent";
    public static final String DEBUG_DUMP_V3 = "api/v3/dbaas/debug/internal/dump";
    public static final String DEBUG_DATABASES_V3 = "api/v3/dbaas/debug/internal/databases";
    public static final String ACCESS_GRANTS_V3 = "api/v3/dbaas/namespaces/%s/services/%s/access-grants";

    public static final String REQUEST_ID = "requestId";

    private final LinkedList<Endpoint> portForwardURLs = new LinkedList<>();

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> chain.proceed(chain.request().newBuilder().addHeader("Connection", "close").build()))
            .build();

    private static final Pattern TEST_NAMESPACE_PATTERN = Pattern.compile("^dbaas-autotests-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @NonNull
    private volatile URL dbaasServiceUrl;
    @NonNull
    private final KubernetesClient kubernetesClient;
    @Getter
    private final String clusterDbaAuthorization;
    @Getter
    private final String backupDaemonAuthorization;
    @Getter
    private final String dbaasDbEditorAuthorization;
    @Getter
    private final String dbaasMigrationAuthorization;
    @NonNull
    private final PortForwardService portForwardService;

    public ObjectMapper objectMapper = new ObjectMapper();

    public DbaasHelperV3(@NotNull URL dbaasServiceUrl, @NotNull KubernetesClient kubernetesClient, @NotNull DbaasUsersData dbaasUsers, @NotNull PortForwardService portForwardService) {
        this.dbaasServiceUrl = dbaasServiceUrl;
        this.kubernetesClient = kubernetesClient;
        this.portForwardService = portForwardService;
        this.clusterDbaAuthorization = dbaasUsers.getBasicAuthorizationForRoles("NAMESPACE_CLEANER", "DB_CLIENT", "MIGRATION_CLIENT");
        this.backupDaemonAuthorization = dbaasUsers.getBasicAuthorizationForRoles("BACKUP_MANAGER");
        this.dbaasDbEditorAuthorization = dbaasUsers.getBasicAuthorizationForRoles("DBAAS_DB_EDITOR");
        this.dbaasMigrationAuthorization = dbaasUsers.getBasicAuthorizationForRoles("MIGRATION_CLIENT");
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
    }

    public URL getDbaasServiceUrl() {
        return dbaasServiceUrl;
    }

    public void setDbaasServiceUrl(@NotNull URL dbaasServiceUrl) {
        this.dbaasServiceUrl = dbaasServiceUrl;
    }

    /**
     * Regex to search for service name and namespace in the database connection string.
     * <p>Explanation:
     * <p>Example string: 'http://elasticsearch.dbaas.svc.cluster.local:9200/dbaas_autotests-69c0c8ab-0a87-4fbc-ac3b-5b57b2d97bc3'
     * <p><b>group 1</b>: line beginning and protocol, if exists, e.g. 'http://'
     * <p><b>group 2</b>: service name we are looking for, e.g. 'elasticsearch'
     * <p><b>group 3</b>: namespace we are looking for, e.g. 'dbaas'
     * <p><b>group 4</b>: some cluster info, if exists, e.g. '.svc.cluster.local',
     * <p><b>group 5</b>: port, without semicolon, if exists, e.g. '9200',
     * <p><b>group 6</b>: the rest of the line, if exists, and line ending,
     * e.g. '/dbaas_autotests-69c0c8ab-0a87-4fbc-ac3b-5b57b2d97bc3'
     */
    private static final Pattern SERVICE_NAME_WITH_NAMESPACE_PATTERN = Pattern.compile("(^.*?://|^)(.*?)\\.(.*?)(\\..*?)?:([0-9]+)(/?.*?$|$)");

    /**
     * Checks whether dbaas-aggregator has physical database adapter of the corresponding type.
     *
     * @param dbType physical database type, e.g. {@code mongodb}.
     * @return {@code true} if dbaas-aggregator has adapters of this type, {@code false} otherwise.
     */


    public boolean hasAdapterOfType(String dbType) {
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(PHYSICAL_DATABASE_MAIN_PATH_V3, dbType))
                .addHeader("Authorization", "Basic " + clusterDbaAuthorization)
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
            RegisteredPhysicalDatabasesDTOV3 physicalDatabases = objectMapper.readValue(body, RegisteredPhysicalDatabasesDTOV3.class);
            for (PhysicalDatabaseRegistrationResponseDTOV3 database : physicalDatabases.getIdentified().values()) {
                if (database.isGlobal() && adapterIsAvailable(database.getAdapterAddress())) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            log.debug("Error while getting adapter by db type {}:", dbType, e);
            return false;
        }
    }

    public boolean adapterIsAvailable(String adapterAddress) {
        Call call = okHttpClient.newCall(new Request.Builder()
                .url(createPortForward(adapterAddress).toString() + "health")
                .addHeader("X-Request-Id", getRequestId())
                .build());
        try (Response response = call.execute()) {
            String healthJson = response.body().string();
            log.info("Adapter {} health: {}", adapterAddress, healthJson);
            return response.code() == 200;
        } catch (IOException e) {
            log.debug("Error while checking adapter {} health:", adapterAddress, e);
            return false;
        }
    }

    public static Optional<String> readEnvVariable(Pod pod, String variableName) {
        return pod.getSpec().getContainers().getFirst().getEnv().stream()
                .filter(envVar -> variableName.equals(envVar.getName()) && StringUtils.isNotBlank(envVar.getValue()))
                .findAny()
                .flatMap(envVar -> Optional.of(envVar.getValue()));
    }

    public Secret getKubernetesClientSecret(String namespace, String secretName) {
        Objects.requireNonNull(secretName, "Secret name must be not null");
        return kubernetesClient.secrets().inNamespace(namespace).withName(secretName).get();
    }

    public Optional<String> readSecretFromEnvVariable(Pod pod, String namespace, String variableName) {
        return pod.getSpec().getContainers().getFirst().getEnv().stream()
                .filter(env -> variableName.equals(env.getName()))
                .findAny()
                .map(EnvVar::getValueFrom)
                .map(EnvVarSource::getSecretKeyRef)
                .map(secretKeySelector -> new String(Base64.getDecoder().decode(
                        getKubernetesClientSecret(namespace, secretKeySelector.getName())
                                .getData().get(secretKeySelector.getKey()))));

    }

    public Pod getPodByServiceName(String namespace, String serviceName) {
        return kubernetesClient.pods().inNamespace(namespace).withLabel("name", serviceName).list().getItems().getFirst();
    }

    private Optional<String> getAdapterAddressByDbTypeAndId(String dbType, String adapterId) {
        try {
            return getRegisteredPhysicalDatabases(dbType, clusterDbaAuthorization, 200)
                    .getIdentified().values().stream().filter(regPhysicalDb -> adapterId.equals(regPhysicalDb.getAdapterId()))
                    .findAny()
                    .map(PhysicalDatabaseRegistrationResponseDTO::getAdapterAddress);
        } catch (Exception e) {
            log.error("An error occurred while receiving registered physical databases.", e);
            return Optional.empty();
        }
    }

    public void deleteLogicalDatabasesInNamespaces(List<String> namespaces) throws IOException {
        var request = new Request.Builder()
                .url(dbaasServiceUrl + String.format("api/v3/dbaas/databases/deleteall?namespaces=%s", String.join(",", namespaces)))
                .addHeader("Authorization", "Basic " + getClusterDbaAuthorization())
                .addHeader("X-Request-Id", getRequestId())
                .delete()
                .build();

        try (var response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);

            assertThat(response.code(), is(HttpStatus.SC_OK));

            // wait for all logical databases to be deleted
            Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(2))).run(() -> {
                var logicalDatabases = this.findLogicalDatabasesByNamespaces(namespaces);

                assertTrue(logicalDatabases.isEmpty(), "Namespaces still have the following logical databases: " +
                        logicalDatabases.stream()
                                .map(DatabaseV3::getId)
                                .collect(Collectors.joining(","))
                );
            });
        }
    }

    public void deleteNamespaceBackupsInNamespaces(List<String> namespaces) throws IOException {
        var request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(BACKUPS_DELETE_V3 + "?forceRemoveNotDeletableBackups=true&namespaces=%s", String.join(",", namespaces)))
                .addHeader("Authorization", "Basic " + getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", getRequestId())
                .delete()
                .build();

        try (var response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);

            assertThat(response.code(), is(HttpStatus.SC_OK));

            // wait for all namespace backups to be deleted
            Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(2))).run(() -> {
                var namespaceBackups = this.findNamespaceBackupsByNamespaces(namespaces);

                assertTrue(namespaceBackups.isEmpty(), "Namespaces still have the following namespace backups: " +
                        namespaceBackups.stream()
                                .map(NamespaceBackupV3::getId)
                                .map(UUID::toString)
                                .collect(Collectors.joining(","))
                );
            });
        }
    }

    public void deleteDatabases(String authorization, String namespace) throws IOException {
        deleteDatabases(DATABASES_V3, authorization, namespace, HttpStatus.SC_OK);
    }

    public String deleteDatabases(String api, String authorization, String namespace, int httpCode) throws IOException {
        Request request = deleteDatabasesRequest(api, authorization, namespace);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            assertThat(response.code(), is(httpCode));
            // wait for all databases to get deleted
            Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(2)).withDelay(Duration.ofSeconds(1))).run(() -> {
                List<DatabaseV3> databases = this.getDatabasesByNamespace(namespace);
                assertTrue(databases.isEmpty(), "Namespace still have the following databases: " +
                        databases.stream().map(DatabaseV3::getName).collect(Collectors.joining(",")));
            });
            return response.body() != null ? response.body().string() : null;
        }
    }

    public DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder getSimpleDatabaseCreateRequest(String microserviceName,
                                                                                                 String testClassifierValue,
                                                                                                 String originService,
                                                                                                 String type) {
        return DatabaseCreateRequestV3.builder(new ClassifierBuilder().test(testClassifierValue).ms(microserviceName).build(), type)
                .namePrefix("dbaas_autotests")
                .originService(originService);
    }

    public DatabaseCreateRequestV3 getCreateDeclarativeDatabaseRequest(String originService, String type, String role) {
        DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder requestBuilder = getSimpleDatabaseCreateRequest(
                TEST_DECLARATIVE_MICROSERVICE_NAME,
                "dbaas_auto_test_1",
                originService,
                type);
        if (role != null) requestBuilder.userRole(role);
        return requestBuilder.build();

    }

    public void createServicesRoles(String type, Boolean disableGlobalPermissions) {
        SecurityRuleConfigurationRequest rolesRegistrationRequest = getRolesRegistrationRequest(type, disableGlobalPermissions);

        Request request = createRequest(String.format(DECLARATIVE_SERVICE_URL, DbaasHelperV3.TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME),
                getClusterDbaAuthorization(),
                rolesRegistrationRequest,
                "POST");

        executeRequest(request, null, 200);
    }

    public SecurityRuleConfigurationRequest getRolesRegistrationRequest(String type, Boolean disableGlobalPermissions) {
        SecurityRuleConfigurationRequest rolesRegistrationRequest = new SecurityRuleConfigurationRequest();
        PolicyRole policyRole = new PolicyRole();
        policyRole.setAdditionalRole(Arrays.asList("ro", Role.RW.getRoleValue()));
        policyRole.setDefaultRole(Role.ADMIN.getRoleValue());
        policyRole.setType(type);

        ServiceRole rwServiceRole = new ServiceRole();
        rwServiceRole.setName("rw-service");
        rwServiceRole.setRoles(Collections.singletonList(Role.RW.getRoleValue()));

        ServiceRole roServiceRole = new ServiceRole();
        roServiceRole.setName("ro-service");
        roServiceRole.setRoles(Collections.singletonList("ro"));
        rolesRegistrationRequest.setApiVersion("nc.core.dbaas/v3");
        rolesRegistrationRequest.setKind("dbPolicy");
        rolesRegistrationRequest.setPolicy(Collections.singletonList(policyRole));
        rolesRegistrationRequest.setServices(Arrays.asList(rwServiceRole, roServiceRole));

        rolesRegistrationRequest.setDisableGlobalPermissions(disableGlobalPermissions);
        return rolesRegistrationRequest;
    }

    public void createServicesRoles(String type) {
        createServicesRoles(type, false);
    }

    public void checkConnectionMongo(DatabaseResponse databaseToCheck) {
        checkConnectionMongo(databaseToCheck, false);
    }

    public void checkConnectionMongo(DatabaseResponse databaseToCheck, Boolean expectFail) {
        checkConnectionMongo(databaseToCheck, expectFail, null, null);
    }

    public RegisteredPhysicalDatabasesDTOV3 getRegisteredPhysicalDatabases(String dbType, String authorization, int expectStatusCode) throws IOException {
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(PHYSICAL_DATABASE_MAIN_PATH_V3, dbType))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", getRequestId())
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertThat(response.code(), is(expectStatusCode));
            String body = response.body().string();
            log.info("Registered physical databases for {} type: {}", dbType, body);
            return objectMapper.readValue(body, RegisteredPhysicalDatabasesDTOV3.class);
        }
    }

    public void checkConnectionMongo(DatabaseResponse databaseToCheck, Boolean expectFail, String setData, String checkData) {
        var url = databaseToCheck.getConnectionPropertyAsString("url");

        assertNotNull(url);

        var database = Optional.ofNullable(databaseToCheck.getConnectionPropertyAsString("dbName"))
                .orElse(databaseToCheck.getConnectionPropertyAsString("authDbName"));

        try (var serviceMongoClient = connectMongo(databaseToCheck, expectFail)) {

            try {
                serviceMongoClient.getDatabase(database).getCollection("autotestsData")
                        .insertOne(new Document("testkey", "testvalue"));

                assertEquals("testvalue",
                        serviceMongoClient.getDatabase(database).getCollection("autotestsData")
                                .find(new Document("testkey", "testvalue"))
                                .first().get("testkey")
                );
            } catch (Throwable ex) {
                throw new CannotConnect(ex);
            }

            if (setData != null) {
                var key = "test_key";
                var value = setData + "_value";

                serviceMongoClient.getDatabase(database).getCollection("autotestsCheckingData")
                        .insertOne(new Document(key, value));

                log.info("data {} saved in mongodb  {}", setData, databaseToCheck);
            }

            try {
                if (checkData != null) {
                    var key = "test_key";
                    var value = checkData + "_value";

                    assertEquals(value,
                            serviceMongoClient.getDatabase(database).getCollection("autotestsCheckingData")
                                    .find(new Document(key, value))
                                    .first()
                                    .get(key)
                    );

                    log.info("data {} checked in mongodb db {} ", checkData, databaseToCheck);
                }
            } catch (Throwable ex) {
                log.info("DatabaseV3 {}", databaseToCheck);

                throw new CannotCheckData(ex);
            }
        } catch (Exception v) {
            throw new CannotConnect(v);
        }
    }

    public MongoClient connectMongo(DatabaseResponse databaseToCheck, Boolean expectFail) {
        String url = databaseToCheck.getConnectionPropertyAsString("url");
        String username = databaseToCheck.getConnectionPropertyAsString("username");
        String password = getPassword(databaseToCheck);
        String authDbName = databaseToCheck.getConnectionPropertyAsString("authDbName");
        MongoClientURI internalMongoUrl = new MongoClientURI(url);
        String database = internalMongoUrl.getDatabase();
        assertNotNull(url);
        assertNotNull(username);
        assertNotNull(password);
        assertNotNull(authDbName);
        assertNotNull(database);
        URL mongos = createPortForward(url);
        log.info("Connect to created database");
        if (!expectFail) {
            return new MongoClient(new ServerAddress(mongos.getHost(), mongos.getPort()),
                    MongoCredential.createCredential(username.trim(), authDbName.trim(), password.trim().toCharArray()),
                    MongoClientOptions.builder().build());
        } else {
            log.info("Create mongo connect with timeout 500 ms");
            return new MongoClient(new ServerAddress(mongos.getHost(), mongos.getPort()),
                    MongoCredential.createCredential(username.trim(), authDbName.trim(), password.trim().toCharArray()),
                    MongoClientOptions.builder().serverSelectionTimeout(500).build());
        }
    }

    public ArangoDB connectArangodb(DatabaseResponse databaseToCheck) {
        var arangoUrl = createPortForward(databaseToCheck.getConnectionPropertyAsString("url"));

        // WORKAROUND because ArangoDB Driver can not resolve DNS, e.g. "arangodb-leader.arangodb-namespace",
        // even though it is done port-forward to this DNS with help of
        // com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService.createPortForward
        String hostIP = LocalHostAddressGenerator.get(arangoUrl.getHost()).getHostAddress();
        return new ArangoDB.Builder()
                .host(hostIP, arangoUrl.getPort())
                .user(databaseToCheck.getConnectionPropertyAsString("username"))
                .password(databaseToCheck.getConnectionPropertyAsString("password"))
                .build();
    }

    public void checkConnectionPostgres(DatabaseResponse databaseToCheck) throws SQLException, IOException {
        checkConnectionPostgres(databaseToCheck, null, null);
    }

    public void checkConnectionOpensearch(DatabaseResponse databaseToCheck, String setData, String checkData) {
        String url = databaseToCheck.getConnectionPropertyAsString("url");
        String prefix = databaseToCheck.getConnectionPropertyAsString("resourcePrefix");
        String username = databaseToCheck.getConnectionPropertyAsString("username");
        String password = databaseToCheck.getConnectionPropertyAsString("password");

        URL opensearch = createPortForward(url);

        OpenSearchClient client;
        try {
            var httpHost = new org.apache.hc.core5.http.HttpHost("http", opensearch.getHost(), opensearch.getPort());
            var transportBuilder = ApacheHttpClient5TransportBuilder.builder(httpHost);

            transportBuilder.setHttpClientConfigCallback(httpClientBuilder -> {
                var tlsStrategy = ClientTlsStrategyBuilder.create()
                        .buildAsync();

                var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                        .setTlsStrategy(tlsStrategy)
                        .build();

                var credentialsProvider = new org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider();

                credentialsProvider.setCredentials(
                        new org.apache.hc.client5.http.auth.AuthScope(httpHost),
                        new org.apache.hc.client5.http.auth.UsernamePasswordCredentials(username, password.toCharArray())
                );

                return httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setConnectionManager(connectionManager);
            });
            client = new OpenSearchClient(transportBuilder.build());
        } catch (Throwable ex) {
            throw new CannotConnect(ex);
        }

        String indexName = prefix + "_test_index";

        try {
            var requestExists = new ExistsRequest.Builder().index(indexName).build();

            if (!client.indices().exists(requestExists).value()) {
                var createIndexRequest = new CreateIndexRequest.Builder()
                        .index(indexName)
                        .build();

                client.indices().create(createIndexRequest);
            }

            if (setData != null) {
                var indexRequest = new IndexRequest.Builder<Map<String, String>>()
                        .index(indexName)
                        .id("1")
                        .document(Map.of("test_key", setData))
                        .build();

                client.index(indexRequest);
            }
        } catch (Throwable ex) {
            throw new CannotConnect(ex);
        }

        try {
            if (checkData != null) {
                var getRequest = new GetRequest.Builder()
                        .index(indexName)
                        .id("1")
                        .build();

                var getResponse = client.get(getRequest, Map.class);

                assertEquals(checkData, getResponse.source().get("test_key"));
            }
        } catch (Throwable ex) {
            throw new CannotCheckData(ex);
        }
    }

    public void checkConnectionClickhouse(DatabaseResponse databaseToCheck, String setData, String checkData) throws SQLException, IOException {
        log.info("Check clickhouse connection");

        try (
                var conn = connectClickhouse(databaseToCheck);
                var stmt = conn.createStatement()
        ) {
            var tableName = "autotests";
            var createQuery = String.format("""
                    CREATE TABLE IF NOT EXISTS %s ON CLUSTER '{cluster}'
                    (
                        key String,
                        value String
                    )
                    ENGINE = ReplicatedMergeTree
                    ORDER BY (key)""", tableName);

            stmt.execute(createQuery);

            if (setData != null) {
                var key = "test_key";
                var value = setData + "_value";

                var rs = stmt.executeQuery(String.format("SELECT 1 FROM autotests WHERE key = '%s'", key));

                if (rs.next()) {
                    stmt.execute(String.format("UPDATE autotests SET value = '%s' WHERE key = '%s'", value, key));
                } else {
                    stmt.execute(String.format("INSERT INTO autotests (*) values ('%s', '%s')", key, value));
                }
            }

            try {
                if (checkData != null) {
                    var key = "test_key";
                    var value = checkData + "_value";
                    var query = String.format("SELECT * FROM autotests WHERE key = '%s'", key);

                    var rs = stmt.executeQuery(query);
                    int counter = 0;

                    while (rs.next()) {
                        assertEquals(key, rs.getString("key"));
                        assertEquals(value, rs.getString("value"));
                        counter++;
                    }
                    assertEquals(1, counter);
                    log.info("data {} checked in clickhouse db {} ", checkData, databaseToCheck);
                }
            } catch (Throwable ex) {
                throw new CannotCheckData(ex);
            }
        } catch (Exception ex) {
            throw new CannotConnect(ex);
        }
    }

    public void checkConnectionArangodb(DatabaseResponse databaseToCheck, String setData, String checkData) {
        try {
            var arangoDb = connectArangodb(databaseToCheck);
            var arangoDatabase = arangoDb.db(databaseToCheck.getConnectionPropertyAsString("dbName"));

            var collectionName = "autotests";
            var collection = arangoDatabase.collection(collectionName);

            if (!collection.exists()) {
                collection.create();
            }

            if (setData != null) {
                var key = "test_key";
                var value = checkData + "_value";
                var document = new BaseDocument(key);

                document.addAttribute(key, value);

                if (collection.documentExists(key)) {
                    collection.updateDocument(key, document);
                } else {
                    collection.insertDocument(document);
                }
            }

            try {
                if (checkData != null) {
                    var key = "test_key";
                    var value = checkData + "_value";
                    var document = collection.getDocument(key, BaseDocument.class);

                    assertEquals(value, document.getAttribute(key));
                }
            } catch (Throwable ex) {
                throw new CannotCheckData(ex);
            }
        } catch (Exception ex) {
            throw new CannotConnect(ex);
        }
    }

    public void checkConnectionPostgres(DatabaseResponse databaseToCheck, String setData, String checkData) throws SQLException, IOException {
        try (Connection connection = connectPg(databaseToCheck);
             Statement statement = connection.createStatement()) {
            connectionQuery(statement, databaseToCheck, setData, checkData);
        } catch (Exception v) {
            throw new CannotConnect(v);
        }
    }

    public void checkConnectionPostgresUser(GetOrCreateUserResponse user, String setData, String checkData) {
        try (Connection connection = connectPg(user);
             Statement statement = connection.createStatement()) {
            connectionQuery(statement, user, setData, checkData);
        } catch (Exception v) {
            throw new CannotConnect(v);
        }
    }

    private void connectionQuery(Statement statement, Object user, String setData, String checkData) throws SQLException {
        try {
            assertTrue(statement.executeQuery("select 1;").next(), "Expect postgresql to return 1 row");
        } catch (Throwable ex) {
            throw new CannotConnect(ex);
        }
        statement.execute("create table IF NOT EXISTS autotests( testdata varchar(200) NOT NULL);");
        if (setData != null) {
            statement.execute("insert into autotests (testdata) values( '" + setData + "');");
            log.info("data {} saved in postgres  {}", setData, user);
        }
        try {
            if (checkData != null) {
                assertTrue(
                        statement.executeQuery("select testdata from autotests where testdata='" + checkData + "';").next(), "Expect data to be returned");
                log.info("data {} checked in postgres  {}", checkData, user);
            }
        } catch (Throwable ex) {
            throw new CannotCheckData(ex);
        }
    }

    public Connection connectClickhouse(DatabaseResponse db) throws SQLException {
        String rawUrl = db.getConnectionPropertyAsString("url");
        rawUrl = rawUrl.replace("9000", "8123");
        URL url = createPortForward(rawUrl);
        String username = db.getConnectionPropertyAsString("username");
        String password = getPassword(db);
        String chUrl = "jdbc:clickhouse:" + url + db.getConnectionPropertyAsString("name");
        ClickHouseDataSource dataSource = new ClickHouseDataSource(chUrl, new Properties());
        return dataSource.getConnection(username, password);
    }

    @NotNull
    private String getPassword(@NotNull DatabaseResponse database) {
        return database.getConnectionPropertyAsString("password");
    }

    @NotNull
    private String getUserPassword(@NotNull GetOrCreateUserResponse user) {
        return user.getConnectionPropertyAsString("password");
    }

    public Connection connectPg(DatabaseResponse databaseToCheck) {
        try {
            String url = databaseToCheck.getConnectionPropertyAsString("url");
            String username = databaseToCheck.getConnectionPropertyAsString("username");
            String host = databaseToCheck.getConnectionPropertyAsString("host");
            String port = databaseToCheck.getConnectionPropertyAsInt("port").toString();
            String password = getPassword(databaseToCheck);
            assertNotNull(url);
            assertNotNull(username);
            assertNotNull(password);
            log.info("Got connection {}", databaseToCheck.getConnectionProperties());
            URL postgres = createPortForwardToPostgres(url);
            log.info("Connect to created database by url {}", postgres);
            return DriverManager.getConnection(url.replace(host, postgres.getHost()).replace(port, Integer.toString(postgres.getPort())), username, password);
        } catch (SQLException ex) {
            throw new CannotConnect(ex);
        }
    }

    public Connection connectPg(GetOrCreateUserResponse user) {
        try {
            String url = user.getConnectionPropertyAsString("url");
            String username = user.getConnectionPropertyAsString("username");
            String host = user.getConnectionPropertyAsString("host");
            String port = user.getConnectionPropertyAsString("port");
            String password = getUserPassword(user);
            assertNotNull(url);
            assertNotNull(username);
            assertNotNull(password);
            log.info("Got connection {}", user.getConnectionProperties());
            URL postgres = createPortForwardToPostgres(url);
            log.info("Connect to created database by url {}", postgres);
            return DriverManager.getConnection(url.replace(host, postgres.getHost()).replace(port, Integer.toString(postgres.getPort())), username, password);
        } catch (SQLException ex) {
            throw new CannotConnect(ex);
        }
    }

    public void checkConnectionCassandra(DatabaseResponse databaseToCheck, String setData, String checkData) {
        log.info("Check cassandra connection");

        var url = createPortForward(databaseToCheck.getConnectionPropertyAsString("url"));
        var keyspace = databaseToCheck.getConnectionPropertyAsString("keyspace");
        var password = getPassword(databaseToCheck);
        Session session;

        try (
                var cluster = Cluster.builder()
                        .withoutMetrics()
                        .addContactPoint(url.getHost())
                        .withPort(url.getPort())
                        .withCredentials(databaseToCheck.getConnectionPropertyAsString("username"), password)
                        .build()
        ) {
            try {
                session = cluster.connect(keyspace);

                assertNotNull(session);
            } catch (Throwable e) {
                log.error("got error during connect to cassandra", e);

                throw new CannotConnect(e);
            }

            var createQuery = "CREATE TABLE IF NOT EXISTS autotests (key text PRIMARY KEY, value text);";
            session.execute(createQuery);

            if (setData != null) {
                var insert = QueryBuilder.insertInto(keyspace, "autotests");

                insert.value("key", "test_key");
                insert.value("value", setData + "_value");
                session.execute(insert);
            }

            if (checkData != null) {
                try {
                    var clause = QueryBuilder.eq("key", "test_key");
                    var select = QueryBuilder.select().from(keyspace, "autotests").where(clause);

                    assertEquals(checkData + "_value", session.execute(select).one().getString("value"));

                    log.info("data {} checked in cassandra db {} ", checkData, databaseToCheck);
                } catch (Throwable e) {
                    log.error("got error during check data in cassandra", e);
                    session.close();

                    throw new CannotCheckData(e);
                }
            }

            session.close();
        }
    }

    public String urlNormalize(String host, String url) {
        Boolean isSlashInHost = host.endsWith("/");
        Boolean isSlashInURL = url.startsWith("/");
        String normalizedURL;
        if (isSlashInHost && isSlashInURL) {
            normalizedURL = host + url.substring(1);
        } else if (!isSlashInHost && !isSlashInURL) {
            normalizedURL = host + "/" + url;
        } else {
            normalizedURL = host + url;
        }
        log.info("Normalized url {}", normalizedURL);
        return normalizedURL;
    }

    public List<DatabaseResponse> createDatabases(String authorization, final String testClassifierValue, int expected, String namespace, String... types) {
        return Arrays.stream(types).map(type -> {
            try {
                Map<String, Object> settings = null;

                if (OPENSEARCH_TYPE.equalsIgnoreCase(type)) {
                    settings = Collections.singletonMap("resourcePrefix", true);
                }

                return createDatabase(authorization, testClassifierValue, expected, type, null, namespace, false, settings);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    public DatabaseResponse createDatabase(String api, Map<String, Object> classifier, String type,
                                           Boolean backupDisabled, int expected) throws IOException {
        DatabaseCreateRequestV3 databaseCreateRequestV3 = new DatabaseCreateRequestV3(classifier, type);
        databaseCreateRequestV3.setBackupDisabled(backupDisabled);
        databaseCreateRequestV3.setOriginService(TEST_MICROSERVICE_NAME);
        databaseCreateRequestV3.setUserRole(Role.ADMIN.getRoleValue());
        return createDatabase(api, databaseCreateRequestV3, expected);
    }

    public DatabaseResponse createDatabase(DatabaseCreateRequestV3 databaseCreateRequest, int expected) throws IOException {
        var namespace = databaseCreateRequest.getClassifier().get("namespace");

        return createDatabase(String.format(DATABASES_V3, namespace), databaseCreateRequest, expected, expected);
    }

    public DatabaseResponse createDatabase(String api, DatabaseCreateRequestV3 databaseCreateRequest, int expected) throws IOException {
        Request createDbRequest = createRequest(api, clusterDbaAuthorization, databaseCreateRequest, "PUT");
        log.info("request: {}", createDbRequest);
        return executeRequest(createDbRequest, DatabaseResponse.class, expected);
    }

    public DatabaseResponse createDatabase(String api, DatabaseCreateRequestV3 databaseCreateRequest, Integer... expected) throws IOException {
        Request createDbRequest = createRequest(api, clusterDbaAuthorization, databaseCreateRequest, "PUT");
        return executeRequest(createDbRequest, DatabaseResponse.class, expected);
    }


    public DatabaseResponse createDatabase(String authorization, final String testClassifierValue, int expected, String type,
                                           List<String> initialScriptIds, String namespace, Boolean backupDisabled) throws IOException {
        return createDatabase(authorization, testClassifierValue, expected, type, initialScriptIds, namespace, backupDisabled, null, "dbaas_autotests", null);
    }

    public DatabaseResponse createDatabase(String authorization, final String testClassifierValue, int expected, String type,
                                           List<String> initialScriptIds, String namespace, Boolean backupDisabled, Map<String, Object> settings) throws IOException {
        return createDatabase(authorization, testClassifierValue, expected, type, initialScriptIds, namespace, backupDisabled, null, "dbaas_autotests", settings);
    }

    public DatabaseResponse createDatabase(String authorization, final String testClassifierValue, int expected, String type,
                                           List<String> initialScriptIds, String namespace, Boolean backupDisabled,
                                           String physicalDatabaseId, String prefixName, Map<String, Object> settings) throws IOException {
        return createDatabase(String.format(DATABASES_V3, namespace), authorization, testClassifierValue, expected, type,
                initialScriptIds, namespace, backupDisabled, physicalDatabaseId, prefixName, settings);
    }

    public DatabaseResponse updateDatabase(String authorization, final String testClassifierValue, int expected, String type,
                                           List<String> initialScriptIds, String namespace, Boolean backupDisabled,
                                           String physicalDatabaseId, String prefixName, Map<String, Object> settings) throws IOException {
        return updateDatabase(String.format(DATABASES_V3, namespace), authorization, testClassifierValue, expected, type,
                initialScriptIds, namespace, backupDisabled, physicalDatabaseId, prefixName, settings);
    }

    public DatabaseResponse createDatabase(String uri, String authorization, final String testClassifierValue, int expected, String type,
                                           List<String> initialScriptIds, String namespace, Boolean backupDisabled,
                                           String physicalDatabaseId, String prefixName, Map<String, Object> settings) throws IOException {
        Request request = createDbRequest(uri, authorization, testClassifierValue, type, initialScriptIds, namespace, backupDisabled, physicalDatabaseId, prefixName, settings);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), is(expected));
            return objectMapper.readValue(body, DatabaseResponse.class);
        }
    }

    public DatabaseResponse updateDatabase(String uri, String authorization, final String testClassifierValue, int expected, String type,
                                           List<String> initialScriptIds, String namespace, Boolean backupDisabled,
                                           String physicalDatabaseId, String prefixName, Map<String, Object> settings) throws IOException {
        Request request = createDbRequest(uri, authorization, testClassifierValue, type, initialScriptIds, namespace, backupDisabled, physicalDatabaseId, prefixName, settings);
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), is(expected));
            return objectMapper.readValue(body, DatabaseResponse.class);
        }
    }

    public GetOrCreateUserResponse getOrCreateUser(String api, GetOrCreateUserRequest request, int expected) {
        Request createUserRequest = createRequest(api, clusterDbaAuthorization, request, "PUT");
        log.info("request: {}", createUserRequest);
        return executeRequest(createUserRequest, GetOrCreateUserResponse.class, expected);
    }

    public UserOperationResponse rotateUserPassword(String api, UserOperationRequest request, int expected) {
        Request rotatePasswordRequest = createRequest(api, clusterDbaAuthorization, request, "POST");
        log.info("request: {}", rotatePasswordRequest);
        return executeRequest(rotatePasswordRequest, UserOperationResponse.class, expected);
    }

    public String rotateUserPasswordWithException(String api, UserOperationRequest request, int expected) {
        Request rotatePasswordRequest = createRequest(api, clusterDbaAuthorization, request, "POST");
        log.info("request: {}", rotatePasswordRequest);
        return executeRequest(rotatePasswordRequest, null, expected);
    }

    public void deleteUser(String api, UserOperationRequest request, int expected) {
        Request deleteUserRequest = createRequest(api, clusterDbaAuthorization, request, "DELETE");
        log.info("request: {}", deleteUserRequest);
        executeRequest(deleteUserRequest, null, expected);
    }

    public static String getServiceNameFromUrl(String databaseUrl) {
        Matcher matcher = SERVICE_NAME_WITH_NAMESPACE_PATTERN.matcher(databaseUrl);
        assertTrue(matcher.find());
        return matcher.group(2);
    }

    public static String getServiceNamespaceFromUrl(String databaseUrl) {
        Matcher matcher = SERVICE_NAME_WITH_NAMESPACE_PATTERN.matcher(databaseUrl);
        assertTrue(matcher.find());
        return matcher.group(3);
    }

    public static int getServicePortFromUrl(String databaseUrl) {
        Matcher matcher = SERVICE_NAME_WITH_NAMESPACE_PATTERN.matcher(databaseUrl);
        assertTrue(matcher.find());
        return Integer.parseInt(matcher.group(5));
    }

    public Response getDatabaseByClassifierAsResponse(String authorization, Map<String, Object> classifier, String type, String namespace) throws IOException {
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
        classifierWithRolesRequest.setOriginService((String) classifier.get("microserviceName"));
        String classifierJson = new GsonBuilder().create().toJson(classifierWithRolesRequest);
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(DATABASES_V3, namespace) + "/get-by-classifier/" + type)
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", getRequestId())
                .post(RequestBody.create(classifierJson, JSON))
                .build();
        return okHttpClient.newCall(request).execute();
    }

    Response getDatabaseByClassifierAsResponse(String authorization, Map<String, Object> classifier, String type, String namespace, String role) throws IOException {
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(role);
        classifierWithRolesRequest.setOriginService((String) classifier.get("microserviceName"));
        String classifierJson = new GsonBuilder().create().toJson(classifierWithRolesRequest);
        log.debug("request body: {}", classifierJson);
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(DATABASES_V3, namespace) + "/get-by-classifier/" + type)
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", getRequestId())
                .post(RequestBody.create(classifierJson, JSON))
                .build();
        return okHttpClient.newCall(request).execute();
    }

    public DatabaseResponse getDatabaseByClassifierAsPOJO(String authorization, Map<String, Object> classifier, String namespace, String type, int expectCode) throws IOException {
        try (Response responseByClassifier = getDatabaseByClassifierAsResponse(authorization, classifier, type, namespace)) {
            log.info("Response: {}", responseByClassifier);
            assertThat(responseByClassifier.code(), is(expectCode));
            String body = responseByClassifier.body().string();
            log.debug("Response body: {}", body);
            return StringUtils.isNotBlank(body) ? objectMapper.readValue(body, DatabaseResponse.class) : null;
        }
    }

    public DatabaseResponse getDatabaseByClassifierAsPOJO(String authorization, Map<String, Object> classifier, String namespace, String type, int expectCode, String role) throws IOException {
        try (Response responseByClassifier = getDatabaseByClassifierAsResponse(authorization, classifier, type, namespace, role)) {
            log.info("Response: {}", responseByClassifier);
            assertThat(responseByClassifier.code(), is(expectCode));
            String body = responseByClassifier.body().string();
            log.debug("Response body: {}", body);
            return StringUtils.isNotBlank(body) ? objectMapper.readValue(body, DatabaseResponse.class) : null;
        }
    }

    private Request deleteDatabasesRequest(String authorization, String namespace) {
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format(DATABASES_DELETE_V3, namespace))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", getRequestId())
                .delete()
                .build();
    }

    private Request deleteDatabasesRequest(String api, String authorization, String namespace) {
        return new Request.Builder()
                .url(dbaasServiceUrl + String.format(api, namespace))
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", getRequestId())
                .delete()
                .build();
    }


    private Request createDbRequest(String authorization, final String testClassifierValue, String type,
                                    List<String> initialScriptIds, String namespace, Boolean backupDisabled, String physicalDatabaseId,
                                    String prefixName, Map<String, Object> settings) {
        return createDbRequest(String.format(DATABASES_V3, namespace), authorization, testClassifierValue, type,
                initialScriptIds, namespace, backupDisabled, physicalDatabaseId, prefixName, settings);
    }

    private Request createDbRequest(String uri, String authorization, final String testClassifierValue, String type,
                                    List<String> initialScriptIds, String namespace, Boolean backupDisabled, String physicalDatabaseId,
                                    String prefixName, Map<String, Object> settings) {
        Map<String, Object> createDbJson = new HashMap<>();
        createDbJson.put("classifier", new ClassifierBuilder().test(testClassifierValue).ns(namespace).build());
        createDbJson.put("namePrefix", prefixName);
        createDbJson.put("type", type);
        createDbJson.put("originService", TEST_MICROSERVICE_NAME);
        if (backupDisabled) {
            createDbJson.put("backupDisabled", backupDisabled);
        }
        if (initialScriptIds != null) {
            createDbJson.put("initScriptIdentifiers", initialScriptIds);
        }
        if (!StringUtils.isEmpty(physicalDatabaseId)) {
            createDbJson.put("physicalDatabaseId", physicalDatabaseId);
        }
        createDbJson.put("settings", settings);
        String createDatabaseReqJson = new GsonBuilder().create().toJson(createDbJson);
        log.info("Create db: " + createDatabaseReqJson);
        return new Request.Builder()
                .url(dbaasServiceUrl + uri)
                .addHeader("X-Request-Id", getRequestId())
                .addHeader("Authorization", "Basic " + authorization)
                .put(RequestBody.create(createDatabaseReqJson, JSON))
                .build();
    }

    private static String getBasicAuthorization(String user, String password) {
        return Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    public List<DatabaseResponse> createDatabaseInOtherPhysicalDatabases(RegisteredPhysicalDatabasesDTO physicalDatabases, String namespace, String dbType) throws IOException {
        List<DatabaseResponse> createdDb = new ArrayList<>();
        for (String physicalDatabaseId : physicalDatabases.getIdentified().keySet()) {
            log.debug("Create database in cluster");
            DatabaseResponse clusterDb = createDatabase(clusterDbaAuthorization, "physical database identifier " + physicalDatabaseId, 201, dbType,
                    null, namespace, false, physicalDatabaseId, "dbaas_autotests", null);
            createdDb.add(clusterDb);
        }
        return createdDb;
    }

    public void checkConnectNotDefaultMongoCluster(DatabaseResponse database, boolean expectFail) {
        try (MongoClient serviceMongoClient = connectMongo(database, expectFail)) {
            serviceMongoClient.getDatabase(database.getName()).getCollection("autotestsData")
                    .insertOne(new Document("testkey", "testvalue"));
            assertEquals(
                    "testvalue",
                    serviceMongoClient.getDatabase(database.getName()).getCollection("autotestsData")
                            .find(new Document("testkey", "testvalue"))
                            .first().get("testkey")
            );
            log.info("Data was inserted successfully");
        }
    }

    public void checkConnectNotDefaultPostgresCluster(DatabaseResponse database) throws SQLException, IOException {
        try (Connection connection = connectPg(database);
             Statement statement = connection.createStatement()) {
            try {
                assertTrue(statement.executeQuery("select 1;").next(), "Expect postgresql to return 1 row");
            } catch (Throwable ex) {
                throw new CannotConnect(ex);
            }
        }
    }

    public PasswordChangeResponse changePassword(Map<String, Object> classifier, String dbType, int expectHttpCode, String namespace) throws IOException {
        PasswordChangRequestV3 passwordChangeRequest = new PasswordChangRequestV3();
        passwordChangeRequest.setClassifier(classifier);
        passwordChangeRequest.setType(dbType);
        passwordChangeRequest.setOriginService(TEST_MICROSERVICE_NAME);
        passwordChangeRequest.setUserRole(Role.ADMIN.getRoleValue());
        return changePassword(passwordChangeRequest, expectHttpCode, namespace);
    }

    private PasswordChangeResponse changePassword(PasswordChangeRequest passwordChangeRequest, int expectHttpCode, String namespace) throws IOException {
        String passwordChangeRequestJson = new GsonBuilder().create().toJson(passwordChangeRequest);
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(CHANGE_PASSWORD_URL_V3, namespace))
                .addHeader("X-Request-Id", getRequestId())
                .addHeader("Authorization", "Basic " + clusterDbaAuthorization)
                .post(RequestBody.create(passwordChangeRequestJson, JSON))
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), is(expectHttpCode));
            return objectMapper.readValue(body, PasswordChangeResponse.class);
        }
    }

    public ExternalDatabaseResponseV3 saveExternalDatabase(String api, Map<String, Object> classifier, String dbType, int expectHttpCode) {
        return saveExternalDatabase(api, classifier, getDefaultConnectionProperties(), dbType, false, expectHttpCode);
    }

    public ExternalDatabaseResponseV3 saveExternalDatabase(String api, Map<String, Object> classifier, List<Map<String, Object>> connectionProperties, String dbType, boolean updateConnectionProperties, int expectHttpCode) {
        ExternalDatabaseRequestV3 externalDatabase = createExternalDatabase(classifier, connectionProperties, dbType);
        externalDatabase.setUpdateConnectionProperties(updateConnectionProperties);
        Request request = createRequest(String.format(api, TEST_NAMESPACE), clusterDbaAuthorization, externalDatabase, "PUT");
        return executeRequest(request, ExternalDatabaseResponseV3.class, expectHttpCode);
    }

    public <T> T executeRequest(Request request, Class<T> clazz, Integer... expectHttpCode) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            String body = response.body().string();
            log.debug("Response body: {}", body);
            assertThat(response.code(), is(oneOf(expectHttpCode)));
            if (clazz == null) {
                return null;
            }
            return objectMapper.readValue(body, clazz);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Response executeRequest(Request request, Integer expectHttpCode) throws IOException {
        Response response = okHttpClient.newCall(request).execute();
        log.info("Response: {}", response);
        assertThat(response.code(), is(expectHttpCode));
        return response;
    }

    public Request createRequest(String url, String authorization, Object body, String method) {
        String content = new GsonBuilder().create().toJson(body);
        log.info("Request body {}", content);
        return new Request.Builder()
                .url(dbaasServiceUrl + url)
                .addHeader("Authorization", "Basic " + authorization)
                .addHeader("X-Request-Id", getRequestId())
                .method(method, body != null ? RequestBody.create(content, JSON) : null)
                .build();
    }

    private ExternalDatabaseRequestV3 createExternalDatabase(Map<String, Object> classifier, List<Map<String, Object>> connectionProperties, String dbType) {
        return ExternalDatabaseRequestV3.builder()
                .classifier(classifier)
                .connectionProperties(connectionProperties)
                .dbName("external-database-test")
                .originService(TEST_MICROSERVICE_NAME)
                .userRole(Role.ADMIN.getRoleValue())
                .type(dbType).build();
    }

    private static List<Map<String, Object>> getDefaultConnectionProperties() {
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("url", "some url");
        connectionProperties.put("host", "some host");
        connectionProperties.put("role", Role.ADMIN.getRoleValue());
        connectionProperties.put("username", "external-user-test");
        connectionProperties.put("password", "password");
        return List.of(connectionProperties);
    }

    public List<String> findAllRegisteredNamespaces() {
        var request = createRequest(DEBUG_NAMESPACES_V3, clusterDbaAuthorization, null, "GET");

        return Arrays.asList(executeRequest(request, String[].class, 200));
    }

    public List<DatabaseV3> getDatabasesByNamespace(String testNamespace) {
        Request request = createRequest(String.format("api/v3/dbaas/%s/databases/list", testNamespace), clusterDbaAuthorization, null, "GET");
        return Arrays.asList(executeRequest(request, DatabaseV3[].class, 200));
    }

    public List<DatabaseV3> findDatabasesByLogicalName(String dbName, @Nullable String testNamespace) {
        return findDatabasesByLogicalName(dbName, testNamespace, false);
    }

    public List<DatabaseV3> findDatabasesByLogicalName(String dbName, @Nullable String testNamespace, Boolean withDecryptedPassword) {
        String url = "api/v3/dbaas/databases/find-by-name/%s";
        if (StringUtils.isNotBlank(testNamespace)) {
            url += "?namespace=" + testNamespace;
        }
        if (withDecryptedPassword) {
            if (!StringUtils.isNotBlank(testNamespace)) url += "?";
            url += "&withDecryptedPassword=true";
        }
        Request request = createRequest(String.format(url, dbName, withDecryptedPassword), clusterDbaAuthorization, null, "GET");
        return Arrays.asList(executeRequest(request, DatabaseV3[].class, 200));
    }

    public List<LostDatabasesResponse> findLostDatabases() {
        String url = "api/v3/dbaas/debug/internal/lost";
        Request request = createRequest(url, clusterDbaAuthorization, null, "GET");
        return Arrays.asList(executeRequest(request, LostDatabasesResponse[].class, 200));
    }

    public List<GhostDatabasesResponse> findGhostDatabases() {
        String url = "api/v3/dbaas/debug/internal/ghost";
        Request request = createRequest(url, clusterDbaAuthorization, null, "GET");
        return Arrays.asList(executeRequest(request, GhostDatabasesResponse[].class, 200));
    }

    public List<DatabaseV3> findLogicalDatabasesByNamespaces(List<String> namespaces) {
        var request = new Request.Builder()
                .url(dbaasServiceUrl + String.format("api/v3/dbaas/databases?namespaces=%s", String.join(",", namespaces)))
                .addHeader("Authorization", "Basic " + getClusterDbaAuthorization())
                .addHeader("X-Request-Id", getRequestId())
                .get()
                .build();

        return Arrays.asList(executeRequest(request, DatabaseV3[].class, 200));
    }

    public List<NamespaceBackupV3> findNamespaceBackupsByNamespaces(List<String> namespaces) {
        var request = new Request.Builder()
                .url(dbaasServiceUrl + String.format(BACKUPS_V3 + "?namespaces=%s", String.join(",", namespaces)))
                .addHeader("Authorization", "Basic " + getBackupDaemonAuthorization())
                .addHeader("X-Request-Id", getRequestId())
                .get()
                .build();

        return Arrays.asList(executeRequest(request, NamespaceBackupV3[].class, 200));
    }

    public Request createGetDumpOfDbaasDatabaseInformationRequest(String acceptHeaderValue) {
        return new Request.Builder()
                .url(dbaasServiceUrl + DEBUG_DUMP_V3)
                .addHeader("Authorization", "Basic " + clusterDbaAuthorization)
                .addHeader("X-Request-Id", getRequestId())
                .addHeader("Accept", acceptHeaderValue)
                .get()
                .build();
    }

    public Request createFindDebugLogicalDatabasesRequest(String filterRsqlQuery) {
        var url = dbaasServiceUrl + DEBUG_DATABASES_V3;

        if (StringUtils.isNotBlank(filterRsqlQuery)) {
            url += "?filter=" + filterRsqlQuery;
        }

        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Basic " + clusterDbaAuthorization)
                .addHeader("X-Request-Id", getRequestId())
                .get()
                .build();
    }

    public URL createPortForward(String url) {
        return this.createPortForward(getServiceNamespaceFromUrl(url),
                getServiceNameFromUrl(url),
                getServicePortFromUrl(url), null);
    }

    public URL createPortForwardWithScheme(String url, String scheme) {
        return this.createPortForward(getServiceNamespaceFromUrl(url),
                getServiceNameFromUrl(url),
                getServicePortFromUrl(url),
                scheme);
    }

    URL createPortForwardToPostgres(String url) {
        log.info("initial url: {}", url);
        String serviceNamespaceFromUrl = getServiceNamespaceFromUrl(url);
        String serviceNameFromUrl = getServiceNameFromUrl(url);
        int servicePortFromUrl = getServicePortFromUrl(url);
        log.info("url partitions, namespace '{}', service name '{}', port '{}'", serviceNamespaceFromUrl, serviceNameFromUrl, servicePortFromUrl);
        return this.createPortForward(serviceNamespaceFromUrl,
                serviceNameFromUrl,
                servicePortFromUrl,
                null);
    }

    private URL createPortForward(String namespace, String serviceName, int port, String schema) {
        if (schema == null) {
            schema = "http";
        }
        var params = ServicePortForwardParams.builder(serviceName, port).namespace(namespace).build();
        NetSocketAddress portForward = portForwardService.portForward(params);
        portForwardURLs.add(new Endpoint(portForward.getHostName(), portForward.getPort()));
        return portForward.toUrl(schema);
    }

    public void closePortForwardConnections() {
        while (!portForwardURLs.isEmpty()) {
            portForwardService.closePortForward(portForwardURLs.pollFirst());
        }
    }

    public boolean isAdapterV2Available(String dbType) {
        PhysicalDatabaseRegistrationResponseDTOV3 physicalDatabase;
        try {
            RegisteredPhysicalDatabasesDTOV3 registeredPhysicalDatabases = getRegisteredPhysicalDatabases(dbType, clusterDbaAuthorization, 200);
            physicalDatabase = registeredPhysicalDatabases.getIdentified().values().iterator().next();
        } catch (IOException e) {
            log.error("Adapter with dbType = {} does not exist", dbType);
            return false;
        }
        return physicalDatabase.getSupportedVersion().equals("v2");
    }


    public boolean isTLSEnabledInAdapter(String dbType, String physicalDatabaseId) {
        String adapterAddress = getAdapterAddress(dbType, physicalDatabaseId);
        assertNotNull(adapterAddress);
        return adapterAddress.contains(HTTPS);
    }

    public boolean isTLSEnabledInDatabase(String dbType, String physicalDatabaseId) {
        String adapterAddress = getAdapterAddress(dbType, physicalDatabaseId);
        assertNotNull(adapterAddress);
        String databaseName = getServiceNameFromUrl(adapterAddress);
        String databaseNamespace = getServiceNamespaceFromUrl(adapterAddress);
        Optional<Pod> dbPod = getAdapterPodByServiceName(databaseNamespace, databaseName);
        if (dbPod.isEmpty()) {
            throw new RuntimeException("Could not find adapter pod for database " + databaseName);
        }
        Optional<String> PGSSL = readEnvVariable(dbPod.get(), "PG_SSL");
        return PGSSL.map(s -> s.equals("on")).orElse(false);
    }

    public boolean isDefaultDatabaseDisabled(Pod dbaasPod) {
        Optional<String> isDefaultDatabaseDisabled = readEnvVariable(dbaasPod, "DBAAS_DEFAULT_PHYSICAL_DATABASES_DISABLED");
        return isDefaultDatabaseDisabled.map(s -> s.equals("true")).orElse(false);
    }

    public Optional<Pod> getAdapterPodByServiceName(String namespace, String serviceName) {
        String[] labelNames = new String[]{"name", "app"};
        String[] labelValues = new String[]{serviceName, "nc-dbaas-postgres-adapter", "dbaas-postgres-adapter"};
        for (String labelName : labelNames) {
            for (String labelValue : labelValues) {
                List<Pod> podsWithLabel = kubernetesClient.pods().inNamespace(namespace).withLabel(labelName, labelValue).list().getItems();
                if (!podsWithLabel.isEmpty()) {
                    return Optional.of(podsWithLabel.getFirst());
                }
            }
        }
        return Optional.empty();
    }

    private String getAdapterAddress(String dbType, String physicalDatabaseId) {
        try {
            Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identified = getRegisteredPhysicalDatabases(dbType, clusterDbaAuthorization, 200).getIdentified();
            PhysicalDatabaseRegistrationResponseDTOV3 physicalDatabaseRegistrationResponseDTOV3 = identified.get(physicalDatabaseId);
            return physicalDatabaseRegistrationResponseDTOV3.getAdapterAddress();
        } catch (Exception e) {
            log.error("An error occurred while receiving registered physical databases.", e);
            return null;
        }
    }

    public void deleteDatabasesByClassifierRequest(String namespace, String dbType, ClassifierWithRolesRequest classifierWithRolesRequest, int expected) throws IOException {
        Request request = createRequest(String.format("api/v3/dbaas/%s/databases/%s", namespace, dbType), clusterDbaAuthorization, classifierWithRolesRequest, "DELETE");
        try (Response response = okHttpClient.newCall(request).execute()) {
            assertThat(response.code(), is(expected));
        }
    }

    public AccessGrantsResponse getAccessRoles(String namespace, String microserviceName, Integer expectedCode) {
        Request request = createRequest(String.format(ACCESS_GRANTS_V3, namespace, microserviceName),
                getClusterDbaAuthorization(),
                null,
                "GET");

        return executeRequest(request, AccessGrantsResponse.class, expectedCode);
    }

    public OverallStatusResponse getOverallStatus() {
        String url = "api/v3/dbaas/debug/internal/info";
        Request request = createRequest(url, clusterDbaAuthorization, null, "GET");
        return executeRequest(request, OverallStatusResponse.class, 200);
    }

    public Response sendRequest(String url, Object body, String method) throws IOException {
        Request request = createRequest(url, clusterDbaAuthorization, body, method);
        Response response = okHttpClient.newCall(request).execute();
        log.info("Response: {}", response);
        log.info("Response body: {}", response.peekBody(Long.MAX_VALUE).string());
        return response;
    }

    public String generateTestNamespace() {
        var namespace = String.format("dbaas-autotests-%s", UUID.randomUUID());

        log.info("Generated new test namespace: {}", namespace);

        return namespace;
    }

    public void deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces() {
        log.info("Finding test namespaces for deleting all logical databases and namespace backups");

        var namespaces = findAllRegisteredNamespaces();

        var testNamespaces = namespaces.stream()
                .filter(namespace -> TEST_NAMESPACE_PATTERN.matcher(namespace).matches())
                .toList();

        log.info("Found {} test namespaces: {}", namespaces.size(), namespaces);

        if (!testNamespaces.isEmpty()) {

            log.info("Deleting all logical databases and namespace backups in {} test namespaces {}", testNamespaces.size(), testNamespaces);

            try {
                log.info("Deleting all logical databases in {} test namespaces", testNamespaces.size());

                deleteLogicalDatabasesInNamespaces(testNamespaces);

                log.info("Deleted all logical databases in {} test namespaces", testNamespaces.size());
            } catch (Exception | AssertionFailedError ex) {
                log.error("Error happened during deleting all logical databases in {} test namespaces", testNamespaces.size(), ex);
            }

            try {
                log.info("Deleting all namespace backups in {} test namespaces", testNamespaces.size());

                deleteNamespaceBackupsInNamespaces(testNamespaces);

                log.info("Deleted all namespace backups in {} test namespaces", testNamespaces.size());
            } catch (Exception | AssertionFailedError ex) {
                log.error("Error happened during deleting all namespace backups in {} test namespaces", testNamespaces.size(), ex);
            }

            log.info("Deleted all logical databases and namespace backups in {} test namespaces {}", testNamespaces.size(), testNamespaces);
        } else {
            log.warn("Skipped deleting all logical databases and namespace backups because no test namespaces were found");
        }
    }

    public static String getRequestId() {
        String requestId = MDC.get(REQUEST_ID);
        assertThat("requestId shouldn't be empty", StringUtils.isNotEmpty(requestId));
        return requestId;
    }

    public static void setRequestId(String requestId) {
        MDC.put(REQUEST_ID, requestId);
    }

    public static void regenerateRequestId() {
        setRequestId(UUID.randomUUID().toString());
    }
}
