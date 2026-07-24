package com.netcracker.it.dbaas.helpers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.response.MigrationResult;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.SecretVolumeSource;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static com.netcracker.it.dbaas.test.AbstractIT.POSTGRES_TYPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@RequiredArgsConstructor
public class MigrationHelper {

    protected final DbaasHelperV3 helperV3;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> chain.proceed(chain.request().newBuilder().addHeader("Connection", "close").build()))
            .build();

    private static final String DBAAS_METADATA = "_dbaas_metadata";
    private static final String AGGREGATOR_CREDS_SECRET_NAME = "dbaas-aggregator-credentials.v1";
    private static final String ADAPTER_CREDS_MOUNT_DIR = "dbaas-adapter-credentials";
    public static final String CONNECTION_PROPERTIES = "connectionProperties";

    public static final String BASE_MIGRATE_API = "api/v3/dbaas/migration/databases";
    public static final String MIGRATE_WITH_USER_CREATION_API = BASE_MIGRATE_API + "/with-user-creation";

    public Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> getGlobalPhysicalDbEntry(String dbType) throws IOException {
        return helperV3.getRegisteredPhysicalDatabases(dbType, helperV3.getClusterDbaAuthorization(), 200)
                .getIdentified()
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().isGlobal())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No global physical database found for type: " + dbType));
    }

    public Map createDbViaAdapter(String dbType, String microserviceName, String namespace) throws IOException {
        PhysicalDatabaseRegistrationResponseDTOV3 physDb = getGlobalPhysicalDbEntry(dbType).getValue();
        String adapterAddress = physDb.getAdapterAddress();
        URL portForward = helperV3.createPortForward(adapterAddress);
        String apiVersion = physDb.getSupportedVersion();
        String payload = String.format(
                "{\"metadata\":{\"classifier\":{\"microserviceName\":\"%s\",\"scope\":\"service\",\"namespace\":\"%s\"}}}",
                microserviceName, namespace);
        String adapterCredentials = readAdapterCredentials(adapterAddress);
        Request request = new Request.Builder()
                .url(portForward.toString() + "api/" + apiVersion + "/dbaas/adapter/" + dbType + "/databases")
                .post(RequestBody.create(payload, JSON))
                .addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(adapterCredentials.getBytes()))
                .addHeader("X-Request-Id", getRequestId())
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String resBody = response.body().string();
            log.info("Adapter {} created database: {}", adapterAddress, resBody);
            assumeTrue(response.code() == 201, "dbaas-adapter could not create database, skip test");
            return objectMapper.readValue(resBody, Map.class);
        } catch (IOException e) {
            log.error("Error while creating database via adapter {}", adapterAddress, e);
            throw e;
        }
    }

    @SneakyThrows
    public void removePgMetadata(Map map) {
        log.info("remove pg metadata");
        List<Map<String, Object>> connectionProperties = (List<Map<String, Object>>) map.get(CONNECTION_PROPERTIES);
        Map<String, Object> adminConnectionProperty = connectionProperties.stream().filter(cp -> cp.get("role").equals(Role.ADMIN.getRoleValue())).findFirst().get();
        log.info("connection property {}", adminConnectionProperty);
        DatabaseResponse databaseResponse = DatabaseResponse.builder().connectionProperties(adminConnectionProperty).build();
        try (Connection connection = helperV3.connectPg(databaseResponse);
             Statement statement = connection.createStatement()) {
            try {
                String sql = "DROP TABLE _dbaas_metadata";
                statement.executeUpdate(sql);
            } catch (Throwable ex) {
                throw new CannotConnect(ex);
            }
        }
    }

    @SneakyThrows
    public void removeMongoMetadata(Map map) {
        log.info("remove mongodb metadata");
        List<Map<String, Object>> connectionProperties = (List<Map<String, Object>>) map.get(CONNECTION_PROPERTIES);
        Map<String, Object> adminConnectionProperty = connectionProperties.stream().filter(cp -> cp.get("role").equals(Role.ADMIN.getRoleValue())).findFirst().get();
        log.info("connection property {}", adminConnectionProperty);
        DatabaseResponse databaseResponse = DatabaseResponse.builder().connectionProperties(adminConnectionProperty).build();
        try (MongoClient mongoClient = helperV3.connectMongo(databaseResponse, false)) {
            MongoDatabase mongoDatabase = mongoClient.getDatabase((String) adminConnectionProperty.get("authDbName"));
            mongoDatabase.getCollection("autotestsData").insertOne(new Document("testkey", "testvalue")); // if database does not have any collections mongo drops such db, so we create fake collection
            mongoDatabase.getCollection(DBAAS_METADATA).drop();
            assertFalse(mongoDatabase.listCollectionNames()
                    .into(new ArrayList<>()).contains(DBAAS_METADATA));
        }
    }

    public String readAdapterCredentials(String adapterAddress) {
        String adapterNamespace = DbaasHelperV3.getServiceNamespaceFromUrl(adapterAddress);
        String adapterServiceName = DbaasHelperV3.getServiceNameFromUrl(adapterAddress);

        Pod adapterPod = helperV3.getAdapterPodByServiceName(adapterNamespace, adapterServiceName)
                .orElseThrow(() -> new RuntimeException(
                        "Could not find adapter pod for service " + adapterServiceName
                                + " in namespace: " + adapterNamespace));

        String username = readAdapterCredential(adapterPod, adapterNamespace, "username",
                List.of("DBAAS_ADAPTER_API_USER", "DBAAS_AGGREGATOR_USERNAME"));

        String password = readAdapterCredential(adapterPod, adapterNamespace, "password",
                List.of("DBAAS_ADAPTER_API_PASSWORD", "DBAAS_AGGREGATOR_PASSWORD"));

        return username + ":" + password;
    }

    /**
     * Reads one basic-auth credential of the adapter API. Adapters expose these credentials in one of three ways,
     * depending on their version: through secret-backed environment variables, through a mounted credentials secret,
     * or through the shared aggregator credentials secret.
     */
    private String readAdapterCredential(Pod pod, String namespace, String key, List<String> envNames) {
        return readFirstAvailableSecretFromEnv(pod, namespace, envNames)
                .or(() -> readFromMountedCredentialsSecret(pod, namespace, key))
                .or(() -> readSecretKey(namespace, AGGREGATOR_CREDS_SECRET_NAME, key))
                .orElseThrow(() -> new RuntimeException(
                        "Could not read adapter credential '" + key + "' in namespace " + namespace
                                + ": none of the environment variables " + envNames + " is backed by a secret,"
                                + " the pod has no '" + ADAPTER_CREDS_MOUNT_DIR + "' volume mount,"
                                + " and secret '" + AGGREGATOR_CREDS_SECRET_NAME + "' holds no such key"));
    }

    private Optional<String> readFirstAvailableSecretFromEnv(Pod pod, String namespace, List<String> envNames) {
        for (String envName : envNames) {
            Optional<String> value = helperV3.readSecretFromEnvVariable(pod, namespace, envName);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the secret behind the adapter credentials volume mount and reads a key from it. The secret name is
     * taken from the pod spec rather than hard-coded, because it differs between adapters.
     */
    private Optional<String> readFromMountedCredentialsSecret(Pod pod, String namespace, String key) {
        Container container = pod.getSpec().getContainers().getFirst();
        return container.getVolumeMounts().stream()
                .filter(mount -> StringUtils.removeEnd(mount.getMountPath(), "/").endsWith(ADAPTER_CREDS_MOUNT_DIR))
                .map(VolumeMount::getName)
                .findFirst()
                .flatMap(volumeName -> pod.getSpec().getVolumes().stream()
                        .filter(volume -> volumeName.equals(volume.getName()))
                        .findFirst())
                .map(Volume::getSecret)
                .map(SecretVolumeSource::getSecretName)
                .flatMap(secretName -> readSecretKey(namespace, secretName, key));
    }

    private Optional<String> readSecretKey(String namespace, String secretName, String key) {
        var secret = helperV3.getKubernetesClientSecret(namespace, secretName);
        if (secret == null || secret.getData() == null || !secret.getData().containsKey(key)) {
            return Optional.empty();
        }
        return Optional.of(new String(Base64.getDecoder().decode(secret.getData().get(key))));
    }

    public DatabaseResponse prepareMigratedExternalToInternalPostgresDatabase(String sourceNamespace,
                                                                              String microserviceName,
                                                                              boolean withUserCreation) throws IOException {
        Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physDbEntry = getGlobalPhysicalDbEntry(POSTGRES_TYPE);
        String physicalId = physDbEntry.getKey();
        PhysicalDatabaseRegistrationResponseDTOV3 physDb = physDbEntry.getValue();

        Map adapterResponse = createDbViaAdapter(POSTGRES_TYPE, microserviceName, sourceNamespace);

        removePgMetadata(adapterResponse);

        String dbName = adapterResponse.get("name").toString();
        List<Map<String, Object>> connectionProperties = (List<Map<String, Object>>) adapterResponse.get(CONNECTION_PROPERTIES);

        Map<String, Object> classifier = new ClassifierBuilder().ms(microserviceName).ns(sourceNamespace).build();

        ExternalDatabaseRequestV3 externalRegistrationRequest = ExternalDatabaseRequestV3
                .builder()
                .classifier(classifier)
                .connectionProperties(connectionProperties)
                .dbName(dbName)
                .originService(microserviceName)
                .userRole(Role.ADMIN.getRoleValue())
                .type(POSTGRES_TYPE)
                .build();

        Request externalRegistartionRequest = helperV3.createRequest(String.format(EXTERNALLY_MANAGEABLE_V3, sourceNamespace),
                helperV3.getClusterDbaAuthorization(), externalRegistrationRequest, HttpMethod.PUT.name());

        helperV3.executeRequest(externalRegistartionRequest, ExternalDatabaseResponseV3.class, HttpStatus.CREATED.value());

        migrateExternalPostgresDatabaseAsInternal(
                classifier,
                adapterResponse,
                dbName,
                physicalId,
                physDb,
                withUserCreation,
                sourceNamespace
        );

        DatabaseResponse migratedDb = helperV3.getDatabaseByClassifierAsPOJO(
                helperV3.getClusterDbaAuthorization(),
                classifier,
                sourceNamespace,
                POSTGRES_TYPE,
                HttpStatus.OK.value()
        );

        assertFalse(migratedDb.isExternallyManageable(),
                "Migrated database should not remain externally manageable");
        assertEquals(dbName, migratedDb.getName(),
                "Migrated database should have the same name as the database created via adapter");
        assertEquals(POSTGRES_TYPE, migratedDb.getType(),
                "Migrated database should have PostgreSQL type");
        assertEquals(sourceNamespace, migratedDb.getNamespace(),
                "Migrated database should belong to the source namespace");

        return migratedDb;
    }

    private void migrateExternalPostgresDatabaseAsInternal(Map<String, Object> classifier,
                                                           Map adapterResponse,
                                                           String dbName,
                                                           String physicalId,
                                                           PhysicalDatabaseRegistrationResponseDTOV3 physDb,
                                                           boolean withUserCreation,
                                                           String sourceNamespace) {
        Object requestBody;
        String apiEndpoint;

        if (withUserCreation) {
            apiEndpoint = MIGRATE_WITH_USER_CREATION_API;

            requestBody = new RegisterDatabaseWithUserCreationRequest(
                    classifier,
                    POSTGRES_TYPE,
                    dbName,
                    physicalId,
                    null
            );
        } else {
            apiEndpoint = BASE_MIGRATE_API;

            List<DbResource> resources = helperV3.objectMapper
                    .convertValue(adapterResponse.get("resources"), new TypeReference<>() {
                    });

            requestBody = new RegisterDatabaseRequest(
                    classifier,
                    null,
                    resources,
                    sourceNamespace,
                    physDb.getAdapterId(),
                    POSTGRES_TYPE,
                    dbName,
                    physicalId,
                    null
            );
        }

        Request migrationRequest = helperV3.createRequest(
                apiEndpoint,
                helperV3.getClusterDbaAuthorization(),
                Collections.singletonList(requestBody),
                HttpMethod.PUT.name()
        );

        Map<String, MigrationResult> resultMap = sendMigrationRequest(
                migrationRequest,
                HttpStatus.OK.value()
        );

        MigrationResult migrationResult = resultMap.get(POSTGRES_TYPE);

        assertNotNull(migrationResult,
                "Migration result for PostgreSQL should be present");

        assertEquals(dbName, migrationResult.getMigrated().getFirst(),
                "Migration result should contain migrated database name");

        assertEquals(1, migrationResult.getMigratedDbInfo().size(),
                "Migration result should contain exactly one migrated database info");

        assertEquals(dbName, migrationResult.getMigratedDbInfo().getFirst().getName(),
                "Migrated database info should have expected database name");
    }

    private Map<String, MigrationResult> sendMigrationRequest(Request request, int expectedCode) {
        Map response = helperV3.executeRequest(request, Map.class, expectedCode);

        return helperV3.objectMapper.convertValue(
                response,
                new TypeReference<>() {
                }
        );
    }
}
