package com.netcracker.it.dbaas.test.declarative;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.backup.v1.BackupStatus;
import com.netcracker.it.dbaas.entity.backup.v1.RestoreStatus;
import com.netcracker.it.dbaas.helpers.*;
import com.netcracker.it.dbaas.test.AbstractIT;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.BGHelper.TEST_NAMESPACE_CANDIDATE;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE_PATTERN;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class OperatorIT extends AbstractIT {

    private static BGHelper bgHelper;
    private static BackupHelperV1 backupHelperV1;
    private static BackupHelperV3 backupHelperV3;
    private static BalancingRulesHelperV3 balancingRulesHelperV3;

    // The operator deploy carries no Secret RBAC, so OperatorIT grants it per-namespace Secret
    // access (a Role + RoleBinding) alongside the NamespaceBinding, mirroring real onboarding.
    private static final String OPERATOR_SERVICE_ACCOUNT = "dbaas-operator";
    private static final String OPERATOR_SECRET_RBAC_NAME = "dbaas-operator-secrets";

    @BeforeAll
    static void setUp() throws IOException {
        NAMESPACE = getTestNamespace();
        dbaasOperatorExistOrSkipTests();
        bgHelper = new BGHelper(helperV3);
        backupHelperV1 = new BackupHelperV1(helperV3);
        backupHelperV3 = new BackupHelperV3(helperV3);
        balancingRulesHelperV3 = new BalancingRulesHelperV3(helperV3);
        cleanUp();
        createNamespaceBindingCROrSkipTests();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_INTERNAL_DATABASE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_DATABASE_ACCESS_POLICY)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_MICROSERVICE_BALANCING_RULE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BALANCING_RULE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_PERMANENT_BALANCING_RULE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.secrets()
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.rbac().roleBindings()
                .inNamespace(NAMESPACE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.rbac().roles()
                .inNamespace(NAMESPACE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        deleteAllLogicalDatabases();
    }

    @Override
    protected void closePortForwardAfterTest() {
    }

    @Nested
    @EnableExtension
    class ConcurrentTest {
        @Nested
        @EnableExtension
        @Execution(ExecutionMode.CONCURRENT)
        class CRD {
            @Nested
            @EnableExtension
            class ExternalDatabase {
                @Test
                void testExternalDatabaseCreatedSuccessfully() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "new-db", "");

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
                }

                @Test
                void testExternalDatabaseCreatedWithSecret() throws IOException {
                    String crName = generateName();
                    String secretName = generateName();
                    String microserviceName = generateName();

                    applySecret(secretName, Map.of(
                            "username", "username",
                            "password", "password"
                    ));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-secret", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);
                    var db = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
                    assertConnectionPropertiesContains(db, Map.of(
                            "username", "username",
                            "password", "password"
                    ));
                }

                @Test
                void testExternalDatabaseSecretClaimRotation() {
                    String crName = generateName();
                    String secretName = generateName();
                    String microserviceName = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    applySecret(secretName, Map.of(
                            "username", "username",
                            "password", "A"
                    ));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-secret-rotation", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);
                    waitForDatabaseConnectionProperties(classifier, Map.of(
                            "username", "username",
                            "password", "A"
                    ));
                    String previousLastRequestId = getLastRequestId(CRD_EXTERNAL_DATABASE, cr);

                    updateSecret(secretName, Map.of(
                            "username", "username",
                            "password", "B"
                    ));

                    waitForLastRequestIdChange(CRD_EXTERNAL_DATABASE, cr, previousLastRequestId,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    waitForDatabaseConnectionProperties(classifier, Map.of(
                            "username", "username",
                            "password", "B"
                    ));
                }

                @Test
                void testExternalDatabaseSecretClaimCreatedAfterEDB() {
                    String crName = generateName();
                    String secretName = generateName();
                    String microserviceName = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-secret-recovery", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                    waitForDatabaseNotFound(classifier);

                    applySecret(secretName, Map.of(
                            "username", "recovered-username",
                            "password", "recovered-password"
                    ));

                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    waitForDatabaseConnectionProperties(classifier, Map.of(
                            "username", "recovered-username",
                            "password", "recovered-password"
                    ));
                }

                @Test
                void testExternalDatabaseSecretClaimDeleted() {
                    String crName = generateName();
                    String secretName = generateName();
                    String microserviceName = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    applySecret(secretName, Map.of(
                            "username", "username",
                            "password", "password"
                    ));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-secret-delete", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);
                    waitForDatabaseConnectionProperties(classifier, Map.of(
                            "username", "username",
                            "password", "password"
                    ));

                    deleteSecret(secretName);

                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                }

                @Test
                void testExternalDatabaseSecretClaimSharedByMultipleEDBs() {
                    String secretName = generateName();
                    String crName1 = generateName();
                    String crName2 = generateName();
                    String microserviceName1 = generateName();
                    String microserviceName2 = generateName();
                    var classifier1 = new ClassifierBuilder().ms(microserviceName1).ns(NAMESPACE).build();
                    var classifier2 = new ClassifierBuilder().ms(microserviceName2).ns(NAMESPACE).build();

                    applySecret(secretName, Map.of(
                            "username", "shared-user",
                            "password", "A"
                    ));

                    var cr1 = buildExternalDatabaseCR(crName1, microserviceName1, NAMESPACE, "db-shared-secret-1", secretName);
                    var cr2 = buildExternalDatabaseCR(crName2, microserviceName2, NAMESPACE, "db-shared-secret-2", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr1);
                    createCR(CRD_EXTERNAL_DATABASE, cr2);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr1, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr2, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);
                    waitForDatabaseConnectionProperties(classifier1, Map.of("username", "shared-user", "password", "A"));
                    waitForDatabaseConnectionProperties(classifier2, Map.of("username", "shared-user", "password", "A"));
                    String previousLastRequestId1 = getLastRequestId(CRD_EXTERNAL_DATABASE, cr1);
                    String previousLastRequestId2 = getLastRequestId(CRD_EXTERNAL_DATABASE, cr2);

                    updateSecret(secretName, Map.of(
                            "username", "shared-user",
                            "password", "B"
                    ));

                    waitForLastRequestIdChange(CRD_EXTERNAL_DATABASE, cr1, previousLastRequestId1,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    waitForLastRequestIdChange(CRD_EXTERNAL_DATABASE, cr2, previousLastRequestId2,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    waitForDatabaseConnectionProperties(classifier1, Map.of("username", "shared-user", "password", "B"));
                    waitForDatabaseConnectionProperties(classifier2, Map.of("username", "shared-user", "password", "B"));
                }

                @Test
                void testExternalDatabaseMultipleSecretsInConnectionProperties() {
                    String crName = generateName();
                    String secretNameA = generateName();
                    String secretNameB = generateName();
                    String microserviceName = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    applySecret(secretNameA, Map.of(
                            "username", "admin-user",
                            "password", "admin-A"
                    ));
                    applySecret(secretNameB, Map.of(
                            "username", "rw-user",
                            "password", "rw-A"
                    ));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-multiple-secrets", null);
                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.put("connectionProperties", List.of(
                            buildSecretConnectionProperties("admin", secretNameA),
                            buildSecretConnectionProperties("rw", secretNameB)
                    ));

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);
                    waitForDatabaseConnectionProperties(classifier, "admin", Map.of("username", "admin-user", "password", "admin-A"));
                    waitForDatabaseConnectionProperties(classifier, "rw", Map.of("username", "rw-user", "password", "rw-A"));
                    String previousLastRequestId = getLastRequestId(CRD_EXTERNAL_DATABASE, cr);

                    updateSecret(secretNameA, Map.of(
                            "username", "admin-user",
                            "password", "admin-B"
                    ));

                    waitForLastRequestIdChange(CRD_EXTERNAL_DATABASE, cr, previousLastRequestId,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    waitForDatabaseConnectionProperties(classifier, "admin", Map.of("username", "admin-user", "password", "admin-B"));
                    waitForDatabaseConnectionProperties(classifier, "rw", Map.of("username", "rw-user", "password", "rw-A"));
                    previousLastRequestId = getLastRequestId(CRD_EXTERNAL_DATABASE, cr);

                    updateSecret(secretNameB, Map.of(
                            "username", "rw-user",
                            "password", "rw-B"
                    ));

                    waitForLastRequestIdChange(CRD_EXTERNAL_DATABASE, cr, previousLastRequestId,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    waitForDatabaseConnectionProperties(classifier, "admin", Map.of("username", "admin-user", "password", "admin-B"));
                    waitForDatabaseConnectionProperties(classifier, "rw", Map.of("username", "rw-user", "password", "rw-B"));
                }

                @Test
                void testExternalDatabaseMissingSecret() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-miss-secret", "does-not-exist");

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testExternalDatabaseSecretClaimMissingKey() throws IOException {
                    String crName = generateName();
                    String secretName = generateName();
                    String microserviceName = generateName();

                    applySecret(secretName, Map.of(
                            "irrelevant-key", "irrelevant-value"
                    ));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-miss-secret-key", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testExternalDatabaseSecretClaimEmptyKey() throws IOException {
                    String crName = generateName();
                    String secretName = generateName();
                    String microserviceName = generateName();

                    applySecret(secretName, Map.of(
                            "username", "",
                            "password", "password"
                    ));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-secret-empty-key", secretName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testExternalDatabaseMetadataClassifierNamespacesMismatch() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, generateName(), "db-namespace-conflict", null);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testExternalDatabaseCreateNotUniqueClassifierDb() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String type = "postgresql";

                    var internalDb = helperV3.createDatabase(new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), type, 201);
                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-classifier-conflict", null);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_AGGREGATOR_REJECTED, STATUS_TRUE);

                    var actualDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, type, 200);
                    assertEquals(actualDb.getConnectionProperties(), internalDb.getConnectionProperties());
                    assertFalse(actualDb.isExternallyManageable());
                }

                @Test
                void testExternalDatabaseMissedClassifier() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-missed-classifier", "");
                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.remove("classifier");

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_EXTERNAL_DATABASE, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.classifier: Required value"));

                    assertNull(
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .get()
                    );
                }

                @Test
                void testExternalDatabaseMissedType() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-missed-type", "");
                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.remove("type");

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_EXTERNAL_DATABASE, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.type: Required value"));

                    assertNull(
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .get()
                    );
                }

                @Test
                void testExternalDatabaseMissedName() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-missed-name", "");
                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.remove("dbName");

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_EXTERNAL_DATABASE, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.dbName: Required value"));

                    assertNull(
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .get()
                    );
                }

                @Test
                void testExternalDatabaseEmptyConnectionProperties() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-empty-connection-properties", "");

                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.put("connectionProperties", List.of());

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_EXTERNAL_DATABASE, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.connectionProperties in body should have at least 1 items"));

                    assertNull(
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .get()
                    );
                }

                @Test
                void testExternalDatabaseMissedRole() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-empty-connection-properties", "");

                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.put("connectionProperties", List.of(Map.of("extraProperties", Map.of(TEST_ID, TEST_ID))));

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_EXTERNAL_DATABASE, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.connectionProperties[0].role: Required value"));

                    assertNull(
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .get()
                    );
                }

                @Test
                void testExternalDatabaseTryToUpdateClassifier() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-immutable-classifier", "");
                    createCR(CRD_EXTERNAL_DATABASE, cr);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () ->
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        Map<String, Object> spec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                        // Provide all CRD-required fields so the schema validation passes
                                        // and the XValidation immutability rule is the one that fires.
                                        spec.put("classifier", Map.of(
                                                "microserviceName", generateName(),
                                                "scope", "service"
                                        ));

                                        return r;
                                    }));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.classifier is immutable after creation"));
                }


                @Test
                void testExternalDatabaseTryToUpdateType() {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String updatedType = "arangoDb";

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-immutable-type", "");
                    var spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    var type = spec.get("type");
                    assertNotEquals(updatedType, type);

                    createCR(CRD_EXTERNAL_DATABASE, cr);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () ->
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                        currSpec.put("type", "arangodb");
                                        return r;
                                    }));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.type is immutable after creation"));
                }

                @Test
                void testExternalDatabaseTryToUpdateDbName() {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String updatedDbName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-immutable-dbName", "");
                    var spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    var dbName = spec.get("dbName");
                    assertNotEquals(updatedDbName, dbName);

                    createCR(CRD_EXTERNAL_DATABASE, cr);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () ->
                            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                        currSpec.put("dbName", generateName());
                                        return r;
                                    }));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.dbName is immutable after creation"));
                }

                @Test
                void testExternalDatabaseTryToUpdateConnectionProperties() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();
                    var updatedConnectionProperties = List.of(Map.of("role", "admin", "extraProperties", Map.of(TEST_ID, TEST_ID)));

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-with-immutable-dbName", "");
                    var spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    var connectionProperties = spec.get("connectionProperties");
                    assertNotEquals(updatedConnectionProperties, connectionProperties);

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);

                    var updatedResource = kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .edit(r -> {
                                Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                currSpec.put("connectionProperties", updatedConnectionProperties);
                                return r;
                            });
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, updatedResource, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE, true);

                    var updatedCR = kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .get();
                    var updatedSpec = (Map<String, Object>) updatedCR.getAdditionalProperties().get("spec");
                    assertEquals(List.of(Map.of("role", "admin", "extraProperties", Map.of(TEST_ID, TEST_ID))), updatedSpec.get("connectionProperties"));

                    var updatedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
                    assertEquals(Map.of("role", "admin", TEST_ID, TEST_ID), updatedDb.getConnectionProperties());
                }
            }

            @Nested
            @EnableExtension
            class NamespaceBinding {
                @Test
                void testNamespaceBindingWrongName() {
                    String crName = generateName();
                    var cr = buildNamespaceBindingCR(crName, NAMESPACE, NAMESPACE);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_NAMESPACE_BINDING, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("NamespaceBinding name must be 'binding'"));
                }

                @Test
                void testNamespaceBindingEmptyOperatorNamespace() {
                    var cr = buildNamespaceBindingCR(CR_NAMESPACE_BINDING_NAME, NAMESPACE, "");

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_NAMESPACE_BINDING, cr));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.operatorNamespace in body should be at least 1 chars long"));
                }

                @Test
                void testNamespaceBindingMissingOperatorNamespace() {
                    var cr = buildNamespaceBindingCR(CR_NAMESPACE_BINDING_NAME, NAMESPACE, "");
                    cr.setAdditionalProperty("spec", Map.of());

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () -> createCR(CRD_NAMESPACE_BINDING, cr));
                    log.info("{}", ex.toString());
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.operatorNamespace: Required value"));
                }

                @Test
                void testNamespaceBindingChangeOperatorNamespaceValue() {
                    var cr = buildNamespaceBindingCR(CR_NAMESPACE_BINDING_NAME, NAMESPACE, NAMESPACE);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class,
                            () -> kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        r.setAdditionalProperty("spec", Map.of(
                                                "operatorNamespace", "updated-namespace"
                                        ));
                                        return r;
                                    }));

                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.operatorNamespace is immutable after creation"));
                }

                @Test
                void testNamespaceBindingUpdateMetadata() {
                    String label = generateName();
                    var cr = buildNamespaceBindingCR(CR_NAMESPACE_BINDING_NAME, NAMESPACE, NAMESPACE);

                    kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .edit(r -> {
                                var metadata = r.getMetadata() != null ? r.getMetadata() : new ObjectMeta();
                                metadata.getLabels().put(label, label);
                                return r;
                            });

                    var updatedCR = kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .get();
                    assertTrue(updatedCR.getMetadata().getLabels().containsKey(label));
                }

                @Test
                void testNamespaceBindingTryToCreateSameOne() {
                    var cr = buildNamespaceBindingCR(CR_NAMESPACE_BINDING_NAME, NAMESPACE, NAMESPACE);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class,
                            () -> createCR(CRD_NAMESPACE_BINDING, cr));
                    assertEquals(409, ex.getCode());
                    assertTrue(ex.toString().contains(String.format("namespacebindings.dbaas.netcracker.com \"%s\" already exists", CR_NAMESPACE_BINDING_NAME)));
                }

                @Test
                void testNamespaceBindingDeletionBlockedByAnotherCRD() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "new-db", "");

                    createCR(CRD_EXTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);

                    kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                            .withLabel(TEST_ID, TEST_ID)
                            .delete();

                    var undeletedNamespaceBinding = kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                            .inNamespace(NAMESPACE)
                            .withLabel(TEST_ID, TEST_ID)
                            .list().getItems().getFirst();

                    assertNotNull(undeletedNamespaceBinding.getMetadata().getDeletionTimestamp());
                    assertTrue(undeletedNamespaceBinding.getMetadata().getFinalizers().contains("platform.dbaas.netcracker.com/binding-protection"));
                }
            }

            @Nested
            @EnableExtension
            class InternalDatabase {

                @Test
                void testInternalDatabaseValidMinimalSpec() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql");

                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
                }

                @Test
                void testInternalDatabaseClassifierNamespaceMismatch() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildInternalDatabaseCR(crName, microserviceName, generateName(), "postgresql");

                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testInternalDatabaseLazyAndCloneRejected() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    Map<String, Object> extraSpec = new HashMap<>();
                    extraSpec.put("initialInstantiation", Map.of(
                            "approach", "clone",
                            "sourceClassifier", Map.of(
                                    "namespace", NAMESPACE,
                                    "scope", "service",
                                    "microserviceName", microserviceName)
                    ));

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql", true, extraSpec);

                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testInternalDatabaseCloneWithoutSourceClassifier() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    Map<String, Object> extraSpec = Map.of(
                            "initialInstantiation", Map.of("approach", "clone")
                    );

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);

                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testInternalDatabaseSourceClassifierMicroserviceNameMismatch() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    Map<String, Object> extraSpec = Map.of(
                            "initialInstantiation", Map.of(
                                    "approach", "clone",
                                    "sourceClassifier", Map.of(
                                            "scope", "service",
                                            "namespace", NAMESPACE,
                                            "microserviceName", generateName())
                            )
                    );

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);

                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
                }

                @Test
                void testInternalDatabaseValidCloneConfig() throws IOException, SQLException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var sourceClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                    sourceClassifier.put("customKeys", Map.of(TEST_ID, "source"));
                    DatabaseResponse sourceDb = helperV3.createDatabase(sourceClassifier, "postgresql", 201);

                    helperV3.checkConnectionPostgres(sourceDb, "source-marker", "source-marker");

                    Map<String, Object> extraSpec = Map.of("initialInstantiation", Map.of(
                            "approach", "clone",
                            "sourceClassifier", Map.of(
                                    "scope", "service",
                                    "namespace", NAMESPACE,
                                    "microserviceName", microserviceName,
                                    "customKeys", Map.of(
                                            TEST_ID, "source"
                                    )
                            )
                    ));

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);
                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    Map<String, Object> cloneClassifier = (Map<String, Object>) spec.get("classifier");
                    cloneClassifier.put("customKeys", Map.of(
                            TEST_ID, "clone"
                    ));

                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var cloneClassifierMap = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                    cloneClassifierMap.put("customKeys", Map.of(TEST_ID, "clone"));
                    DatabaseResponse cloneDb = helperV3.getDatabaseByClassifierAsPOJO(
                            helperV3.getClusterDbaAuthorization(), cloneClassifierMap, NAMESPACE, "postgresql", 200);
                    helperV3.checkConnectionPostgres(cloneDb, null, "source-marker");

                    helperV3.checkConnectionPostgres(cloneDb, "clone-marker", "clone-marker");
                    assertThrows(AssertionError.class,
                            () -> helperV3.checkConnectionPostgres(sourceDb, null, "clone-marker"));
                }

                @Test
                void testInternalDatabaseWaitsForCloneSource() throws IOException, SQLException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    Map<String, Object> extraSpec = Map.of("initialInstantiation", Map.of(
                            "approach", "clone",
                            "sourceClassifier", Map.of(
                                    "scope", "service",
                                    "namespace", NAMESPACE,
                                    "microserviceName", microserviceName,
                                    "customKeys", Map.of(
                                            TEST_ID, "source"
                                    )
                            )
                    ));

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);
                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    Map<String, Object> cloneClassifier = (Map<String, Object>) spec.get("classifier");
                    cloneClassifier.put("customKeys", Map.of(
                            TEST_ID, "clone"
                    ));


                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr,
                            PHASE_WAITING_FOR_DEPENDENCY, STATUS_FALSE, REASON_PROVISIONING_STARTED, STATUS_FALSE);

                    var cloneClassifierMap = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                    cloneClassifierMap.put("customKeys", Map.of(TEST_ID, "clone"));
                    helperV3.getDatabaseByClassifierAsPOJO(
                            helperV3.getClusterDbaAuthorization(), cloneClassifierMap, NAMESPACE, "postgresql", 404);

                    var sourceClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                    sourceClassifier.put("customKeys", Map.of(TEST_ID, "source"));
                    var sourceDb = helperV3.createDatabase(sourceClassifier, "postgresql", 201);
                    helperV3.checkConnectionPostgres(sourceDb);

                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);
                    helperV3.getDatabaseByClassifierAsPOJO(
                            helperV3.getClusterDbaAuthorization(), cloneClassifierMap, NAMESPACE, "postgresql", 200);
                }

                @Test
                void testInternalDatabaseTryToUpdateClassifier() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql");
                    createCR(CRD_INTERNAL_DATABASE, cr);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () ->
                            kubernetesClient.genericKubernetesResources(CRD_INTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        Map<String, Object> spec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                        // Provide all CRD-required fields so the schema validation passes
                                        // and the XValidation immutability rule is the one that fires.
                                        spec.put("classifier", Map.of(
                                                "microserviceName", generateName(),
                                                "scope", "service"
                                        ));
                                        return r;
                                    }));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.classifier is immutable after creation"));
                }

                @Test
                void testInternalDatabaseTryToUpdateType() {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String updatedType = "mongodb";

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql");
                    var spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    assertNotEquals(updatedType, spec.get("type"));

                    createCR(CRD_INTERNAL_DATABASE, cr);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () ->
                            kubernetesClient.genericKubernetesResources(CRD_INTERNAL_DATABASE)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                        currSpec.put("type", updatedType);
                                        return r;
                                    }));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.type is immutable after creation"));
                }

                @Test
                void testInternalDatabaseCanUpdateSettings() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildInternalDatabaseCR(crName, microserviceName, NAMESPACE, "postgresql");
                    createCR(CRD_INTERNAL_DATABASE, cr);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, cr,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var updatedSettings = Map.of(TEST_ID, "updated");
                    var updatedResource = kubernetesClient.genericKubernetesResources(CRD_INTERNAL_DATABASE)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .edit(r -> {
                                Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                currSpec.put("settings", updatedSettings);
                                return r;
                            });
                    waitForDesiredState(CRD_INTERNAL_DATABASE, updatedResource,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE, true);

                    var refreshed = kubernetesClient.genericKubernetesResources(CRD_INTERNAL_DATABASE)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .get();
                    var refreshedSpec = (Map<String, Object>) refreshed.getAdditionalProperties().get("spec");
                    assertEquals(updatedSettings, refreshedSpec.get("settings"));
                }
            }

            @Nested
            @EnableExtension
            class DatabaseAccessPolicy {

                @Test
                void testDatabaseAccessPolicyBothServicesAndPolicyEmpty() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var cr = buildDatabaseAccessPolicyCR(crName, microserviceName, List.of(), List.of());

                    createCR(CRD_DATABASE_ACCESS_POLICY, cr);
                    waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                    helperV3.getAccessRoles(NAMESPACE, microserviceName, 404);
                }

                @Test
                void testDatabaseAccessPolicyOnlyServicesSet() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    OperatorIT.this.testDatabaseAccessPolicyOnlyServicesSet(crName, microserviceName, "svc-a", List.of("admin"));
                }

                @Test
                void testDatabaseAccessPolicyOnlyPolicySet() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var policy = Map.<String, Object>of("type", "backup", "defaultRole", "admin");
                    var cr = buildDatabaseAccessPolicyCR(crName, microserviceName, null, List.of(policy));

                    createCR(CRD_DATABASE_ACCESS_POLICY, cr);
                    waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

                    var roles = helperV3.getAccessRoles(NAMESPACE, microserviceName, 200);
                    assertTrue(roles.getServices() == null || roles.getServices().isEmpty());

                    var actualPolicies = roles.getPolicies();
                    assertTrue(actualPolicies != null && !actualPolicies.isEmpty());
                    assertEquals(1, actualPolicies.size());

                    var actualPolicy = actualPolicies.getFirst();
                    assertEquals(policy.get("type"), actualPolicy.getType());
                    assertEquals(policy.get("defaultRole"), actualPolicy.getDefaultRole());
                }

                @Test
                void testDatabaseAccessPolicyBothServicesAndPolicySet() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var service = Map.of("name", "svc-a", "roles", List.of("admin"));
                    var policy = Map.<String, Object>of("type", "backup", "defaultRole", "admin");
                    var cr = buildDatabaseAccessPolicyCR(crName, microserviceName, List.of(service), List.of(policy));

                    createCR(CRD_DATABASE_ACCESS_POLICY, cr);
                    waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

                    var roles = helperV3.getAccessRoles(NAMESPACE, microserviceName, 200);

                    var actualServices = roles.getServices();
                    assertTrue(actualServices != null && !actualServices.isEmpty());
                    assertEquals(1, actualServices.size());

                    var actualService = actualServices.getFirst();
                    assertEquals(service.get("name"), actualService.getName());
                    assertEquals(service.get("roles"), actualService.getRoles());

                    var actualPolicies = roles.getPolicies();
                    assertTrue(actualPolicies != null && !actualPolicies.isEmpty());
                    assertEquals(1, actualPolicies.size());

                    var actualPolicy = actualPolicies.getFirst();
                    assertEquals(policy.get("type"), actualPolicy.getType());
                    assertEquals(policy.get("defaultRole"), actualPolicy.getDefaultRole());
                }

                @Test
                void testDatabaseAccessPolicyDisableGlobalPermissions() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var service = Map.of("name", "svc-a", "roles", List.of("admin"));
                    var cr = buildDatabaseAccessPolicyCR(crName, microserviceName, List.of(service), null);

                    Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                    spec.put("disableGlobalPermissions", true);

                    createCR(CRD_DATABASE_ACCESS_POLICY, cr);
                    waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

                    var roles = helperV3.getAccessRoles(NAMESPACE, microserviceName, 200);
                    assertEquals(Boolean.TRUE, roles.getDisableGlobalPermissions());

                    var actualServices = roles.getServices();
                    assertTrue(actualServices != null && !actualServices.isEmpty());
                    assertEquals(1, actualServices.size());

                    var actualService = actualServices.getFirst();
                    assertEquals(service.get("name"), actualService.getName());
                    assertEquals(service.get("roles"), actualService.getRoles());
                }

                @Test
                void testDatabaseAccessPolicyTryToUpdateMicroserviceName() {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String updatedMicroserviceName = generateName();
                    assertNotEquals(microserviceName, updatedMicroserviceName);

                    var service = Map.of("name", "svc-a", "roles", List.of("admin"));
                    var cr = buildDatabaseAccessPolicyCR(crName, microserviceName, List.of(service), null);
                    createCR(CRD_DATABASE_ACCESS_POLICY, cr);

                    KubernetesClientException ex = assertThrows(KubernetesClientException.class, () ->
                            kubernetesClient.genericKubernetesResources(CRD_DATABASE_ACCESS_POLICY)
                                    .inNamespace(NAMESPACE)
                                    .resource(cr)
                                    .edit(r -> {
                                        Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                        currSpec.put("microserviceName", updatedMicroserviceName);
                                        return r;
                                    }));
                    assertEquals(422, ex.getCode());
                    assertTrue(ex.toString().contains("spec.microserviceName is immutable after creation"));
                }

                @Test
                void testDatabaseAccessPolicyCanUpdateServices() {
                    String crName = generateName();
                    String microserviceName = generateName();

                    var initialService = Map.of("name", "svc-a", "roles", List.of("admin"));
                    var cr = buildDatabaseAccessPolicyCR(crName, microserviceName, List.of(initialService), null);
                    createCR(CRD_DATABASE_ACCESS_POLICY, cr);
                    waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, cr,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

                    var updatedService = Map.of("name", "svc-b", "roles", List.of("readonly"));
                    var updatedResource = kubernetesClient.genericKubernetesResources(CRD_DATABASE_ACCESS_POLICY)
                            .inNamespace(NAMESPACE)
                            .resource(cr)
                            .edit(r -> {
                                Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                                currSpec.put("services", List.of(updatedService));
                                return r;
                            });
                    waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, updatedResource,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE, true);

                    var roles = helperV3.getAccessRoles(NAMESPACE, microserviceName, 200);
                    var actualServices = roles.getServices();
                    assertTrue(actualServices != null && !actualServices.isEmpty());
                    assertEquals(1, actualServices.size());
                    var actualService = actualServices.getFirst();
                    assertEquals(updatedService.get("name"), actualService.getName());
                    assertEquals(updatedService.get("roles"), actualService.getRoles());
                }
            }

            @Nested
            @EnableExtension
            class DatabaseSecretClaim {
                @Test
                void testDatabaseSecretClaimCreatedSuccessfully() throws IOException {
                    String internalDatabaseCRName = generateName();
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();

                    var internalDatabaseCR = buildInternalDatabaseCR(internalDatabaseCRName, microserviceName, NAMESPACE, POSTGRES_TYPE);
                    createCR(CRD_INTERNAL_DATABASE, internalDatabaseCR);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);
                    var expectedConnections = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, POSTGRES_TYPE, 200).getConnectionProperties();

                    var databaseSecretCR = buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDatabaseSecretClaimCR);
                    assertSecretContainsConnectionProperties(secret, CONNECTION_PROPERTIES_KEY, expectedConnections);
                    // userRole is empty in this CR → descriptor must omit it.
                    assertSecretContainsMetadata(secret, microserviceName, NAMESPACE, POSTGRES_TYPE, "");
                }

                @Test
                void testDatabaseSecretClaimSecretAlreadyExistsNoOwnerRef() {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();

                    applySecret(secretName, Map.of(
                            "key", "value"
                    ));

                    var databaseSecretCR = buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_SECRET_CONFLICT, STATUS_TRUE);
                }

                @Test
                void testDatabaseSecretClaimSharedSecretName() {
                    String crName1 = generateName();
                    String crName2 = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();

                    // Two CRs claim the same secretName for a database that does not exist.
                    // The deterministic sibling tiebreak (older creationTimestamp, UID on tie)
                    // makes exactly one CR lose with SecretConflict while the other keeps
                    // claiming the name (BackingOff/DatabaseNotFound) — avoiding the symmetric
                    // deadlock where both fail and neither recovers. Which CR wins is
                    // UID-dependent when both are created within the same second (k8s stores
                    // creationTimestamp at second granularity), so the test asserts the
                    // invariant — exactly one conflict — without assuming an order.
                    var databaseSecretCR1 = buildDatabaseSecretClaimCR(crName1, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var databaseSecretCR2 = buildDatabaseSecretClaimCR(crName2, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR1);
                    createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR2);

                    assertExactlyOneSecretConflict(crName1, crName2);
                }

                @Test
                void testDatabaseSecretClaimMissingLabel() {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String requiredLabel = "app.kubernetes.io/name";

                    var databaseSecretCR = buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    databaseSecretCR.getMetadata().getLabels().remove(requiredLabel);

                    createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);

                    var secret = getSecret(secretName);
                    assertNull(secret);
                }

                @Test
                void testDatabaseSecretClaimDbNotExist() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();

                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                            new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(),
                            NAMESPACE, POSTGRES_TYPE, 404);

                    var databaseSecretCR = buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_BACKING_OFF, STATUS_FALSE, REASON_DATABASE_NOT_FOUND, STATUS_FALSE);

                    assertNull(getSecret(secretName));

                    var expectedConnections = helperV3.createDatabase(new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), POSTGRES_TYPE, 201).getConnectionProperties();
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDatabaseSecretClaimCR);
                    assertSecretContainsConnectionProperties(secret, CONNECTION_PROPERTIES_KEY, expectedConnections);
                }

                @Test
                void testDatabaseSecretClaimCascadeDeleting() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();

                    helperV3.createDatabase(new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), POSTGRES_TYPE, 201);

                    var databaseSecretCR = buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDatabaseSecretClaimCR);

                    kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                            .withName(crName)
                            .delete();

                    var deletedDatabaseSecretClaimCR = kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                            .inNamespace(NAMESPACE)
                            .withName(crName)
                            .delete();
                    assertTrue(deletedDatabaseSecretClaimCR.isEmpty());

                    waitForSecretDeleted(secretName);
                    var deletedSecret = getSecret(secretName);
                    assertNull(deletedSecret);
                }

                @Test
                void testDatabaseSecretClaimApplyDatabaseAccessPolicy() throws IOException {
                    String dbSecretCrName = generateName();
                    String databaseAccessPolicyCrName = generateName();
                    String microserviceName = generateName();
                    String originService = generateName();
                    String secretName = generateName();

                    var expectedConnections = helperV3.createDatabase(
                            new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(),
                            POSTGRES_TYPE, 201
                    ).getConnectionProperties();

                    var databaseSecretCR = buildDatabaseSecretClaimCR(dbSecretCrName, originService, microserviceName, NAMESPACE, secretName, "admin", POSTGRES_TYPE);
                    var failedDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, failedDatabaseSecretClaimCR, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_AGGREGATOR_REJECTED, STATUS_TRUE);
                    assertNull(getSecret(secretName));

                    kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                            .withName(dbSecretCrName)
                            .delete();

                    OperatorIT.this.testDatabaseAccessPolicyOnlyServicesSet(databaseAccessPolicyCrName, microserviceName, originService, List.of("admin"));
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);
                    var secret = getSecret(secretName);
                    assertSecretContainsConnectionProperties(secret, CONNECTION_PROPERTIES_KEY, expectedConnections);
                    // userRole "admin" is requested in this CR → descriptor must carry it.
                    assertSecretContainsMetadata(secret, microserviceName, NAMESPACE, POSTGRES_TYPE, "admin");
                }

                @Test
                void testDatabaseSecretClaimApplyRotation() throws IOException {
                    String internalDatabaseCRName = generateName();
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    var internalDatabaseCR = buildInternalDatabaseCR(internalDatabaseCRName, microserviceName, NAMESPACE, POSTGRES_TYPE);
                    createCR(CRD_INTERNAL_DATABASE, internalDatabaseCR);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var databaseSecretCR = buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secretBefore = getSecret(secretName);
                    assertNotNull(secretBefore, "secret must exist");
                    var passwordBefore = extractPasswordFromSecret(secretBefore);

                    helperV3.changePassword(classifier, POSTGRES_TYPE, 200, NAMESPACE);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);

                    var secretAfter = getSecret(secretName);
                    assertNotNull(secretAfter, "secret must exist after rotation");
                    var passwordAfter = extractPasswordFromSecret(secretAfter);

                    assertNotEquals(passwordBefore, passwordAfter, "password must change after rotation");
                }

                @Test
                void testDatabaseSecretClaimRotationFanOutToSameClassifier() throws IOException {
                    String internalDatabaseCRName = generateName();
                    String dbSecretCRName1 = generateName();
                    String dbSecretCRName2 = generateName();
                    String microserviceName = generateName();
                    String secretName1 = generateName();
                    String secretName2 = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    var internalDatabaseCR = buildInternalDatabaseCR(internalDatabaseCRName, microserviceName, NAMESPACE, POSTGRES_TYPE);
                    createCR(CRD_INTERNAL_DATABASE, internalDatabaseCR);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var databaseSecretCR1 = buildDatabaseSecretClaimCR(dbSecretCRName1, microserviceName, microserviceName, NAMESPACE, secretName1, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR1 = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR1);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR1, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var databaseSecretCR2 = buildDatabaseSecretClaimCR(dbSecretCRName2, microserviceName, microserviceName, NAMESPACE, secretName2, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR2 = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR2);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR2, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var passwordBefore1 = extractPasswordFromSecret(getSecret(secretName1));
                    var passwordBefore2 = extractPasswordFromSecret(getSecret(secretName2));

                    helperV3.changePassword(classifier, POSTGRES_TYPE, 200, NAMESPACE);

                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR1, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR2, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);

                    var passwordAfter1 = extractPasswordFromSecret(getSecret(secretName1));
                    var passwordAfter2 = extractPasswordFromSecret(getSecret(secretName2));

                    assertNotEquals(passwordBefore1, passwordAfter1, "secret 1 password must change after rotation");
                    assertNotEquals(passwordBefore2, passwordAfter2, "secret 2 password must change after rotation");
                }

                @Test
                void testDatabaseSecretClaimRotationTriggeredByBackupRestore() throws IOException {
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                    helperV3.createDatabase(classifier, POSTGRES_TYPE, 201);

                    var databaseSecretCR = buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var passwordBefore = extractPasswordFromSecret(getSecret(secretName));

                    var backupRequest = new BackupRequestBuilder()
                            .filterCriteria(fc -> fc.include(f -> f.ms(microserviceName)))
                            .build();
                    var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
                    assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

                    var restoreRequest = new RestoreRequestBuilder().build();
                    var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
                    assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);
                    var passwordAfter = extractPasswordFromSecret(getSecret(secretName));
                    assertNotEquals(passwordBefore, passwordAfter, "secret password must change after rotation");
                }

                @Test
                void testDatabaseSecretClaimUpdatedAfterBackupRestoreV3() throws IOException {
                    String sourceNamespace = helperV3.generateTestNamespace();
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();

                    var sourceClassifier = new ClassifierBuilder().ms(microserviceName).ns(sourceNamespace).build();
                    helperV3.createDatabase(sourceClassifier, POSTGRES_TYPE, 201);

                    var targetClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                    helperV3.createDatabase(targetClassifier, POSTGRES_TYPE, 201);

                    var databaseSecretCR = buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    String passwordBefore = extractPasswordFromSecret(getSecret(secretName));

                    var backupRequest = new BackupRequestBuilder()
                            .filterCriteria(fc -> fc.include(f -> f.ms(microserviceName).ns(sourceNamespace)))
                            .build();
                    var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
                    assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

                    var restoreRequest = new RestoreRequestBuilder()
                            .mapping(m -> m.ns(sourceNamespace, NAMESPACE))
                            .build();
                    var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
                    assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);

                    String passwordAfter = extractPasswordFromSecret(getSecret(secretName));
                    assertNotEquals(passwordBefore, passwordAfter, "secret password must change after cross-namespace restore");
                }

                @Test
                void testDatabaseSecretClaimExtraKeysAndCustomKeysClassifierShape() throws IOException {
                    String edbCRName = generateName();
                    String dscCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String extraKeyValue = "eu-west";
                    String customKeyValue = "billing";

                    // ExternalDatabase with extraKeys and customKeys in the classifier.
                    var edbCR = buildExternalDatabaseCR(edbCRName, microserviceName, NAMESPACE, "billing-db", "");
                    Map<String, Object> spec = (Map<String, Object>) edbCR.getAdditionalProperties().get("spec");
                    Map<String, Object> classifier = (Map<String, Object>) spec.get("classifier");
                    classifier.put("extraKeys", Map.of("region", extraKeyValue));
                    classifier.put("customKeys", Map.of("logicalDBName", customKeyValue));

                    createCR(CRD_EXTERNAL_DATABASE, edbCR);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, edbCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);

                    // DatabaseSecretClaim with the same classifier.
                    var dscCR = buildDatabaseSecretClaimCR(dscCRName, microserviceName, microserviceName, NAMESPACE, secretName, "admin", POSTGRES_TYPE);
                    Map<String, Object> dscSpec = (Map<String, Object>) dscCR.getAdditionalProperties().get("spec");
                    Map<String, Object> dscClassifier = (Map<String, Object>) dscSpec.get("classifier");
                    dscClassifier.put("extraKeys", Map.of("region", extraKeyValue));
                    dscClassifier.put("customKeys", Map.of("logicalDBName", customKeyValue));

                    var createdDscCR = createCR(CRD_DATABASE_SECRET_CLAIM, dscCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDscCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDscCR);
                    assertSecretContainsMetadata(secret, microserviceName, NAMESPACE, POSTGRES_TYPE, "admin");

                    // Decode metadata.json to verify classifier shape.
                    String decodedJson = new String(
                            java.util.Base64.getDecoder().decode(secret.getData().get(METADATA_KEY)),
                            java.nio.charset.StandardCharsets.UTF_8
                    );
                    JsonNode metaClassifier =
                            new ObjectMapper()
                                    .readTree(decodedJson)
                                    .path("classifier");

                    // extraKeys must be flattened to the top level alongside microserviceName/scope.
                    assertEquals(extraKeyValue, metaClassifier.path("region").asText(),
                            "extraKeys entry 'region' must sit at classifier top level");
                    assertFalse(metaClassifier.has("extraKeys"),
                            "extraKeys wrapper key must not appear in the flattened classifier");

                    // customKeys must remain nested, not promoted to the top level.
                    JsonNode customKeys = metaClassifier.path("customKeys");
                    assertFalse(customKeys.isMissingNode(),
                            "customKeys must be a nested object inside the classifier");
                    assertEquals(customKeyValue, customKeys.path("logicalDBName").asText(),
                            "customKeys entry 'logicalDBName' must be nested under classifier.customKeys");
                    assertFalse(metaClassifier.has("logicalDBName"),
                            "customKeys entry 'logicalDBName' must not be promoted to classifier top level");
                }
            }

            // Tenant counterpart of DatabaseSecretClaim: the same flows with a tenant-scoped
            // classifier (scope=tenant + a per-test tenantId). The InternalDatabase-backed cases
            // (Created/ApplyRotation/RotationFanOut) additionally exercise the operator's
            // pinned-tenant materialization — without it the concrete {scope=tenant, tenantId}
            // database is never created and the claim stays on DatabaseNotFound.
            @Nested
            @EnableExtension
            class DatabaseSecretClaimTenant {
                @Test
                void testDatabaseSecretClaimTenantCreatedSuccessfully() throws IOException {
                    String internalDatabaseCRName = generateName();
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();

                    var internalDatabaseCR = asTenant(
                            buildInternalDatabaseCR(internalDatabaseCRName, microserviceName, NAMESPACE, POSTGRES_TYPE), tenantId);
                    createCR(CRD_INTERNAL_DATABASE, internalDatabaseCR);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var tenantClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build();
                    var expectedConnections = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), tenantClassifier, NAMESPACE, POSTGRES_TYPE, 200).getConnectionProperties();

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDatabaseSecretClaimCR);
                    assertSecretContainsConnectionProperties(secret, CONNECTION_PROPERTIES_KEY, expectedConnections);
                    // userRole is empty in this CR → descriptor must omit it; scope/tenantId must be carried.
                    assertSecretContainsMetadata(secret, microserviceName, NAMESPACE, POSTGRES_TYPE, "", "tenant", tenantId);
                }

                @Test
                void testDatabaseSecretClaimTenantDbNotExist() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();
                    var tenantClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build();

                    helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                            tenantClassifier, NAMESPACE, POSTGRES_TYPE, 404);

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_BACKING_OFF, STATUS_FALSE, REASON_DATABASE_NOT_FOUND, STATUS_FALSE);

                    assertNull(getSecret(secretName));

                    var expectedConnections = helperV3.createDatabase(tenantClassifier, POSTGRES_TYPE, 201).getConnectionProperties();
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDatabaseSecretClaimCR);
                    assertSecretContainsConnectionProperties(secret, CONNECTION_PROPERTIES_KEY, expectedConnections);
                }

                @Test
                void testDatabaseSecretClaimTenantCascadeDeleting() throws IOException {
                    String crName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();

                    helperV3.createDatabase(new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build(), POSTGRES_TYPE, 201);

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDatabaseSecretClaimCR);

                    kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                            .withName(crName)
                            .delete();

                    var deletedDatabaseSecretClaimCR = kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                            .inNamespace(NAMESPACE)
                            .withName(crName)
                            .delete();
                    assertTrue(deletedDatabaseSecretClaimCR.isEmpty());

                    waitForSecretDeleted(secretName);
                    assertNull(getSecret(secretName));
                }

                @Test
                void testDatabaseSecretClaimTenantApplyDatabaseAccessPolicy() throws IOException {
                    String dbSecretCrName = generateName();
                    String databaseAccessPolicyCrName = generateName();
                    String microserviceName = generateName();
                    String originService = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();

                    var expectedConnections = helperV3.createDatabase(
                            new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build(),
                            POSTGRES_TYPE, 201
                    ).getConnectionProperties();

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCrName, originService, microserviceName, NAMESPACE, secretName, "admin", POSTGRES_TYPE), tenantId);
                    var failedDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, failedDatabaseSecretClaimCR, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_AGGREGATOR_REJECTED, STATUS_TRUE);
                    assertNull(getSecret(secretName));

                    kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM)
                            .withName(dbSecretCrName)
                            .delete();

                    OperatorIT.this.testDatabaseAccessPolicyOnlyServicesSet(databaseAccessPolicyCrName, microserviceName, originService, List.of("admin"));
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);
                    var secret = getSecret(secretName);
                    assertSecretContainsConnectionProperties(secret, CONNECTION_PROPERTIES_KEY, expectedConnections);
                    // userRole "admin" is requested in this CR → descriptor must carry it, plus scope/tenantId.
                    assertSecretContainsMetadata(secret, microserviceName, NAMESPACE, POSTGRES_TYPE, "admin", "tenant", tenantId);
                }

                @Test
                void testDatabaseSecretClaimTenantApplyRotation() throws IOException {
                    String internalDatabaseCRName = generateName();
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build();

                    var internalDatabaseCR = asTenant(
                            buildInternalDatabaseCR(internalDatabaseCRName, microserviceName, NAMESPACE, POSTGRES_TYPE), tenantId);
                    createCR(CRD_INTERNAL_DATABASE, internalDatabaseCR);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secretBefore = getSecret(secretName);
                    assertNotNull(secretBefore, "secret must exist");
                    var passwordBefore = extractPasswordFromSecret(secretBefore);

                    helperV3.changePassword(classifier, POSTGRES_TYPE, 200, NAMESPACE);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);

                    var secretAfter = getSecret(secretName);
                    assertNotNull(secretAfter, "secret must exist after rotation");
                    var passwordAfter = extractPasswordFromSecret(secretAfter);

                    assertNotEquals(passwordBefore, passwordAfter, "password must change after rotation");
                }

                @Test
                void testDatabaseSecretClaimTenantRotationFanOutToSameClassifier() throws IOException {
                    String internalDatabaseCRName = generateName();
                    String dbSecretCRName1 = generateName();
                    String dbSecretCRName2 = generateName();
                    String microserviceName = generateName();
                    String secretName1 = generateName();
                    String secretName2 = generateName();
                    String tenantId = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build();

                    var internalDatabaseCR = asTenant(
                            buildInternalDatabaseCR(internalDatabaseCRName, microserviceName, NAMESPACE, POSTGRES_TYPE), tenantId);
                    createCR(CRD_INTERNAL_DATABASE, internalDatabaseCR);
                    waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

                    var databaseSecretCR1 = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCRName1, microserviceName, microserviceName, NAMESPACE, secretName1, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR1 = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR1);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR1, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var databaseSecretCR2 = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCRName2, microserviceName, microserviceName, NAMESPACE, secretName2, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR2 = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR2);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR2, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var passwordBefore1 = extractPasswordFromSecret(getSecret(secretName1));
                    var passwordBefore2 = extractPasswordFromSecret(getSecret(secretName2));

                    helperV3.changePassword(classifier, POSTGRES_TYPE, 200, NAMESPACE);

                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR1, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR2, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);

                    var passwordAfter1 = extractPasswordFromSecret(getSecret(secretName1));
                    var passwordAfter2 = extractPasswordFromSecret(getSecret(secretName2));

                    assertNotEquals(passwordBefore1, passwordAfter1, "secret 1 password must change after rotation");
                    assertNotEquals(passwordBefore2, passwordAfter2, "secret 2 password must change after rotation");
                }

                @Test
                void testDatabaseSecretClaimTenantRotationTriggeredByBackupRestore() throws IOException {
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();
                    var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build();

                    helperV3.createDatabase(classifier, POSTGRES_TYPE, 201);

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var passwordBefore = extractPasswordFromSecret(getSecret(secretName));

                    var backupRequest = new BackupRequestBuilder()
                            .filterCriteria(fc -> fc.include(f -> f.ms(microserviceName)))
                            .build();
                    var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
                    assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

                    var restoreRequest = new RestoreRequestBuilder().build();
                    var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
                    assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);
                    var passwordAfter = extractPasswordFromSecret(getSecret(secretName));
                    assertNotEquals(passwordBefore, passwordAfter, "secret password must change after rotation");
                }

                @Test
                void testDatabaseSecretClaimTenantUpdatedAfterBackupRestoreV3() throws IOException {
                    String sourceNamespace = helperV3.generateTestNamespace();
                    String dbSecretCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();

                    var sourceClassifier = new ClassifierBuilder().ms(microserviceName).ns(sourceNamespace).tenant().tenantId(tenantId).build();
                    helperV3.createDatabase(sourceClassifier, POSTGRES_TYPE, 201);

                    var targetClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).tenant().tenantId(tenantId).build();
                    helperV3.createDatabase(targetClassifier, POSTGRES_TYPE, 201);

                    var databaseSecretCR = asTenant(
                            buildDatabaseSecretClaimCR(dbSecretCRName, microserviceName, microserviceName, NAMESPACE, secretName, "", POSTGRES_TYPE), tenantId);
                    var createdDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDatabaseSecretClaimCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    String passwordBefore = extractPasswordFromSecret(getSecret(secretName));

                    var backupRequest = new BackupRequestBuilder()
                            .filterCriteria(fc -> fc.include(f -> f.ms(microserviceName).ns(sourceNamespace)))
                            .build();
                    var backupResponse = backupHelperV1.runBackupAndWait(backupRequest, false);
                    assertEquals(BackupStatus.COMPLETED, backupResponse.getStatus());

                    var restoreRequest = new RestoreRequestBuilder()
                            .mapping(m -> m.ns(sourceNamespace, NAMESPACE))
                            .build();
                    var restoreResponse = backupHelperV1.runRestoreAndWait(backupRequest.getBackupName(), restoreRequest, false);
                    assertEquals(RestoreStatus.COMPLETED, restoreResponse.getStatus());

                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_ROTATED, STATUS_FALSE);

                    String passwordAfter = extractPasswordFromSecret(getSecret(secretName));
                    assertNotEquals(passwordBefore, passwordAfter, "secret password must change after cross-namespace restore");
                }

                @Test
                void testDatabaseSecretClaimTenantExtraKeysAndCustomKeysClassifierShape() throws IOException {
                    String edbCRName = generateName();
                    String dscCRName = generateName();
                    String microserviceName = generateName();
                    String secretName = generateName();
                    String tenantId = generateName();
                    String extraKeyValue = "eu-west";
                    String customKeyValue = "billing";

                    // ExternalDatabase with a tenant classifier plus extraKeys and customKeys.
                    var edbCR = asTenant(buildExternalDatabaseCR(edbCRName, microserviceName, NAMESPACE, "billing-db", ""), tenantId);
                    Map<String, Object> spec = (Map<String, Object>) edbCR.getAdditionalProperties().get("spec");
                    Map<String, Object> classifier = (Map<String, Object>) spec.get("classifier");
                    classifier.put("extraKeys", Map.of("region", extraKeyValue));
                    classifier.put("customKeys", Map.of("logicalDBName", customKeyValue));

                    createCR(CRD_EXTERNAL_DATABASE, edbCR);
                    waitForDesiredState(CRD_EXTERNAL_DATABASE, edbCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);

                    // DatabaseSecretClaim with the same tenant classifier.
                    var dscCR = asTenant(buildDatabaseSecretClaimCR(dscCRName, microserviceName, microserviceName, NAMESPACE, secretName, "admin", POSTGRES_TYPE), tenantId);
                    Map<String, Object> dscSpec = (Map<String, Object>) dscCR.getAdditionalProperties().get("spec");
                    Map<String, Object> dscClassifier = (Map<String, Object>) dscSpec.get("classifier");
                    dscClassifier.put("extraKeys", Map.of("region", extraKeyValue));
                    dscClassifier.put("customKeys", Map.of("logicalDBName", customKeyValue));

                    var createdDscCR = createCR(CRD_DATABASE_SECRET_CLAIM, dscCR);
                    waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, createdDscCR, PHASE_SUCCEEDED, STATUS_TRUE, REASON_SECRET_CREATED, STATUS_FALSE);

                    var secret = getSecret(secretName);
                    assertSecretOwnedByCR(secret, createdDscCR);
                    assertSecretContainsMetadata(secret, microserviceName, NAMESPACE, POSTGRES_TYPE, "admin", "tenant", tenantId);

                    // Decode metadata.json to verify classifier shape.
                    String decodedJson = new String(
                            java.util.Base64.getDecoder().decode(secret.getData().get(METADATA_KEY)),
                            java.nio.charset.StandardCharsets.UTF_8
                    );
                    JsonNode metaClassifier =
                            new ObjectMapper()
                                    .readTree(decodedJson)
                                    .path("classifier");

                    // extraKeys must be flattened to the top level alongside microserviceName/scope/tenantId.
                    assertEquals(extraKeyValue, metaClassifier.path("region").asText(),
                            "extraKeys entry 'region' must sit at classifier top level");
                    assertFalse(metaClassifier.has("extraKeys"),
                            "extraKeys wrapper key must not appear in the flattened classifier");

                    // customKeys must remain nested, not promoted to the top level.
                    JsonNode customKeys = metaClassifier.path("customKeys");
                    assertFalse(customKeys.isMissingNode(),
                            "customKeys must be a nested object inside the classifier");
                    assertEquals(customKeyValue, customKeys.path("logicalDBName").asText(),
                            "customKeys entry 'logicalDBName' must be nested under classifier.customKeys");
                    assertFalse(metaClassifier.has("logicalDBName"),
                            "customKeys entry 'logicalDBName' must not be promoted to classifier top level");
                }
            }

            // BalancingRules CRs are singletons (fixed names), so unlike the sibling
            // classes these tests cannot use generateName() per test. They run
            // sequentially within this class (JUnit only parallelises the sibling
            // @Nested classes against each other), and @AfterEach deletes each
            // singleton CR — the finalizer cleanup clears the rule from the aggregator,
            // resetting both k8s and aggregator state between tests.
            @Nested
            @EnableExtension
            class BalancingRules {

                @AfterEach
                void cleanBalancingRules() {
                    deleteBalancingRuleAndWait(CRD_MICROSERVICE_BALANCING_RULE, CR_MICROSERVICE_BALANCING_RULE_NAME);
                    deleteBalancingRuleAndWait(CRD_NAMESPACE_BALANCING_RULE, CR_NAMESPACE_BALANCING_RULE_NAME);
                    deleteBalancingRuleAndWait(CRD_PERMANENT_BALANCING_RULE, CR_PERMANENT_BALANCING_RULE_NAME);
                }

                // ---------- MicroserviceBalancingRule ----------

                @Test
                void testMicroserviceBalancingRuleAppliedSuccessfully() throws IOException {
                    var label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
                    assumeTrue(label != null, "No unique labels in physical databases");
                    String microserviceName = generateName();

                    var cr = buildMicroserviceBalancingRuleCR(List.of(Map.of(
                            "type", POSTGRES_TYPE,
                            "microservices", List.of(microserviceName),
                            "label", label.getKey() + "=" + label.getValue())));

                    createCR(CRD_MICROSERVICE_BALANCING_RULE, cr);
                    waitForDesiredState(CRD_MICROSERVICE_BALANCING_RULE, cr,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_BALANCING_RULE_APPLIED, STATUS_FALSE);

                    assertTrue(getOnMicroserviceRules().stream()
                                    .anyMatch(rule -> microserviceName.equals(rule.getMicroservice())),
                            "aggregator must hold an onMicroservices rule for the microservice");
                }

                @Test
                void testMicroserviceBalancingRuleDuplicateMicroserviceInvalid() throws IOException {
                    String microserviceName = generateName();
                    // Two rule entries of the same type sharing one microservice -> validateMicroserviceRule rejects.
                    var cr = buildMicroserviceBalancingRuleCR(List.of(
                            Map.of("type", POSTGRES_TYPE, "microservices", List.of(microserviceName), "label", "zone=a"),
                            Map.of("type", POSTGRES_TYPE, "microservices", List.of(microserviceName), "label", "zone=b")));

                    createCR(CRD_MICROSERVICE_BALANCING_RULE, cr);
                    waitForDesiredState(CRD_MICROSERVICE_BALANCING_RULE, cr,
                            PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);

                    assertTrue(getOnMicroserviceRules().stream()
                                    .noneMatch(rule -> microserviceName.equals(rule.getMicroservice())),
                            "aggregator must not be mutated when the spec is rejected");
                }

                // ---------- NamespaceBalancingRule ----------

                @Test
                void testNamespaceBalancingRuleAppliedSuccessfully() throws IOException {
                    String physDbId = getAnyRegisteredPhysDbId(POSTGRES_TYPE);
                    String microserviceName = generateName();

                    var cr = buildNamespaceBalancingRuleCR(List.of(Map.of(
                            "name", generateName(),
                            "type", POSTGRES_TYPE,
                            "physicalDatabaseId", physDbId,
                            "order", 0L)));

                    createCR(CRD_NAMESPACE_BALANCING_RULE, cr);
                    waitForDesiredState(CRD_NAMESPACE_BALANCING_RULE, cr,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_BALANCING_RULE_APPLIED, STATUS_FALSE);

                    // No GET endpoint for namespace rules -> verify via the debug endpoint, like BalancingRulesV3IT.
                    var pgDebug = debugRulesForMicroservice(microserviceName).get(POSTGRES_TYPE);
                    assertNotNull(pgDebug);
                    assertEquals(DebugRulesDbTypeData.NAMESPACE_RULE_INFO, pgDebug.getAppliedRuleInfo());
                    assertEquals(physDbId, pgDebug.getPhysicalDbIdentifier());
                }

                @Test
                void testNamespaceBalancingRuleDuplicateOrderInvalid() {
                    // Two rules with the same type and same order -> validateNamespaceRule rejects.
                    var cr = buildNamespaceBalancingRuleCR(List.of(
                            Map.of("name", "rule-a", "type", POSTGRES_TYPE, "physicalDatabaseId", "id-1", "order", 0L),
                            Map.of("name", "rule-b", "type", POSTGRES_TYPE, "physicalDatabaseId", "id-2", "order", 0L)));

                    createCR(CRD_NAMESPACE_BALANCING_RULE, cr);
                    waitForDesiredState(CRD_NAMESPACE_BALANCING_RULE, cr,
                            PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                }

                // ---------- PermanentBalancingRule ----------

                @Test
                void testPermanentBalancingRuleAppliedSuccessfully() throws IOException {
                    String physDbId = getAnyRegisteredPhysDbId(POSTGRES_TYPE);
                    // CR lives in the operator namespace (== NAMESPACE) and targets an owned namespace (NAMESPACE).
                    var cr = buildPermanentBalancingRuleCR(List.of(Map.of(
                            "dbType", POSTGRES_TYPE,
                            "physicalDatabaseId", physDbId,
                            "namespaces", List.of(NAMESPACE))));

                    createCR(CRD_PERMANENT_BALANCING_RULE, cr);
                    waitForDesiredState(CRD_PERMANENT_BALANCING_RULE, cr,
                            PHASE_SUCCEEDED, STATUS_TRUE, REASON_BALANCING_RULE_APPLIED, STATUS_FALSE);

                    assertTrue(getPermanentRules(NAMESPACE).stream()
                                    .anyMatch(rule -> POSTGRES_TYPE.equals(rule.getDbType())
                                            && physDbId.equals(rule.getPhysicalDatabaseId())),
                            "aggregator must hold the permanent rule for the namespace");
                }

                @Test
                void testPermanentBalancingRuleDuplicateNamespaceInvalid() throws IOException {
                    // Two rules with the same dbType and an overlapping namespace -> validatePermanentRule rejects.
                    var cr = buildPermanentBalancingRuleCR(List.of(
                            Map.of("dbType", POSTGRES_TYPE, "physicalDatabaseId", "id-1", "namespaces", List.of(NAMESPACE)),
                            Map.of("dbType", POSTGRES_TYPE, "physicalDatabaseId", "id-2", "namespaces", List.of(NAMESPACE))));

                    createCR(CRD_PERMANENT_BALANCING_RULE, cr);
                    waitForDesiredState(CRD_PERMANENT_BALANCING_RULE, cr,
                            PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);

                    assertTrue(getPermanentRules(NAMESPACE).isEmpty(),
                            "aggregator must not be mutated when the spec is rejected");
                }
            }
        }
    }

    @Test
    void testDatabaseSecretClaimDatabaseIsAbsentInActiveNamespace() throws IOException {
        assumeTrue(helperV3.findLogicalDatabasesByNamespaces(List.of(NAMESPACE)).isEmpty(), String.format("Namespace '%s' must be empty to create BG domain", NAMESPACE));
        String crName = generateName();
        String microserviceName = generateName();
        String secretName = generateName();

        try (Response initResponse = bgHelper.initDomain(NAMESPACE, TEST_NAMESPACE_CANDIDATE, NAMESPACE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        var databaseSecretCR = buildDatabaseSecretClaimCR(crName, microserviceName, microserviceName, NAMESPACE, secretName, "admin", POSTGRES_TYPE);
        var failedDatabaseSecretClaimCR = createCR(CRD_DATABASE_SECRET_CLAIM, databaseSecretCR);
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, failedDatabaseSecretClaimCR, PHASE_BACKING_OFF, STATUS_FALSE, REASON_AGGREGATOR_ERROR, STATUS_FALSE);
        assertNull(getSecret(secretName));
        try (Response response = bgHelper.destroyDomain(new BgNamespaceRequest(NAMESPACE, TEST_NAMESPACE_CANDIDATE))) {
            assertEquals(200, response.code());
        }
    }

    private void testDatabaseAccessPolicyOnlyServicesSet(String crName, String originService, String microserviceName, List<String> roles) {
        var service = Map.of("name", microserviceName, "roles", roles);
        var cr = buildDatabaseAccessPolicyCR(crName, originService, List.of(service), null);

        createCR(CRD_DATABASE_ACCESS_POLICY, cr);
        waitForDesiredState(CRD_DATABASE_ACCESS_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

        var accessRoles = helperV3.getAccessRoles(NAMESPACE, originService, 200);
        assertTrue(accessRoles.getPolicies() == null || accessRoles.getPolicies().isEmpty());

        var actualServices = accessRoles.getServices();
        assertTrue(actualServices != null && !actualServices.isEmpty());
        assertEquals(1, accessRoles.getServices().size());

        var actualService = actualServices.getFirst();
        assertEquals(actualService.getName(), service.get("name"));
        assertEquals(actualService.getRoles(), service.get("roles"));
    }

    private void applySecret(String secretName, Map<String, String> keyToValue) {
        ObjectMeta meta = new ObjectMeta();
        meta.setName(secretName);
        meta.setNamespace(NAMESPACE);
        meta.setLabels(Map.of(TEST_ID, TEST_ID));

        Secret secret = new SecretBuilder()
                .withMetadata(meta)
                .withStringData(keyToValue)
                .build();

        kubernetesClient.secrets()
                .inNamespace(NAMESPACE)
                .resource(secret)
                .create();
    }

    private void updateSecret(String secretName, Map<String, String> keyToValue) {
        Secret existing = kubernetesClient.secrets()
                .inNamespace(NAMESPACE)
                .withName(secretName)
                .get();
        assertNotNull(existing, "secret must exist before update");

        existing.setStringData(keyToValue);

        kubernetesClient.secrets()
                .inNamespace(NAMESPACE)
                .resource(existing)
                .update();
    }

    private void deleteSecret(String secretName) {
        kubernetesClient.secrets()
                .inNamespace(NAMESPACE)
                .withName(secretName)
                .delete();
    }


    private Secret getSecret(String secretName) {
        ObjectMeta meta = new ObjectMeta();
        meta.setName(secretName);
        meta.setNamespace(NAMESPACE);

        Secret secret = new SecretBuilder()
                .withMetadata(meta)
                .build();

        return kubernetesClient.secrets()
                .inNamespace(NAMESPACE)
                .resource(secret)
                .get();
    }

    // waitForSecretDeleted polls until the owned Secret is gone. Deleting the
    // DatabaseSecretClaim CR removes its Secret via owner-reference garbage
    // collection, which is asynchronous, so callers must wait rather than assert
    // immediately. fabric8 passes null to the predicate once the resource is
    // absent. The exception is swallowed so the caller's assertNull surfaces a
    // clear failure if GC did not complete within the timeout.
    private void waitForSecretDeleted(String secretName) {
        try {
            kubernetesClient.secrets()
                    .inNamespace(NAMESPACE)
                    .withName(secretName)
                    .waitUntilCondition(Objects::isNull, 2, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Catch later
        }
    }

    // assertExactlyOneSecretConflict waits until exactly one of the two DatabaseSecretClaim CRs
    // that share a secretName loses the sibling tiebreak (InvalidConfiguration/SecretConflict)
    // while the other keeps claiming it (BackingOff/DatabaseNotFound). It does NOT assume which
    // CR wins: when both CRs are created within the same second their creationTimestamps are
    // equal (k8s second granularity) and the winner is decided by UID, so the outcome is
    // deterministic but order-independent.
    private void assertExactlyOneSecretConflict(String crNameA, String crNameB) {
        var dsClient = kubernetesClient.genericKubernetesResources(CRD_DATABASE_SECRET_CLAIM).inNamespace(NAMESPACE);
        try {
            dsClient.withName(crNameA).waitUntilCondition(a -> {
                var b = dsClient.withName(crNameB).get();
                return a != null && b != null && exactlyOneSecretConflict(a, b);
            }, 2, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Catch later — the assertion below produces a clear failure message.
        }
        var a = dsClient.withName(crNameA).get();
        var b = dsClient.withName(crNameB).get();
        assertTrue(exactlyOneSecretConflict(a, b),
                "exactly one of two DatabaseSecretClaim CRs sharing a secretName must be in "
                        + "InvalidConfiguration/SecretConflict and the other in BackingOff/DatabaseNotFound");
    }

    private boolean exactlyOneSecretConflict(GenericKubernetesResource a, GenericKubernetesResource b) {
        boolean aConflict = isDesiredState(a, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_SECRET_CONFLICT, STATUS_TRUE);
        boolean bConflict = isDesiredState(b, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_SECRET_CONFLICT, STATUS_TRUE);
        boolean aClaiming = isDesiredState(a, PHASE_BACKING_OFF, STATUS_FALSE, REASON_DATABASE_NOT_FOUND, STATUS_FALSE);
        boolean bClaiming = isDesiredState(b, PHASE_BACKING_OFF, STATUS_FALSE, REASON_DATABASE_NOT_FOUND, STATUS_FALSE);
        return (aConflict && bClaiming) || (aClaiming && bConflict);
    }

    private GenericKubernetesResource createCR(CustomResourceDefinitionContext crd, GenericKubernetesResource cr) {
        GenericKubernetesResource r = kubernetesClient.genericKubernetesResources(crd)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .create();
        log.info("CR created: {}", r);
        return r;
    }

    private Map<String, Object> buildSecretConnectionProperties(String role, String secretName) {
        return Map.of(
                "role", role,
                "credentialsSecretRef", Map.of(
                        "name", secretName,
                        "keys", List.of(
                                Map.of("key", "username", "name", "username"),
                                Map.of("key", "password", "name", "password")
                        )
                )
        );
    }

    private void waitForDesiredState(CustomResourceDefinitionContext crd, GenericKubernetesResource cr, String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling) {
        waitForDesiredState(crd, cr, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling, false);
    }

    private void waitForDesiredState(CustomResourceDefinitionContext crd, GenericKubernetesResource cr, String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling, boolean waitForObservedGeneration) {
        try {
            kubernetesClient.genericKubernetesResources(crd)
                    .inNamespace(NAMESPACE)
                    .resource(cr)
                    .waitUntilCondition(r -> isDesiredState(r, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling)
                                    && (!waitForObservedGeneration || isCurrentGenerationObserved(r)),
                            2, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Catch later
        }
        GenericKubernetesResource resultCR = kubernetesClient.genericKubernetesResources(crd)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .get();
        assertDesiredState(resultCR, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling);
        if (waitForObservedGeneration) {
            assertTrue(isCurrentGenerationObserved(resultCR), "current generation must be observed");
        }
    }

    private void waitForLastRequestIdChange(CustomResourceDefinitionContext crd, GenericKubernetesResource cr, String previousLastRequestId,
                                            String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling) {
        try {
            kubernetesClient.genericKubernetesResources(crd)
                    .inNamespace(NAMESPACE)
                    .resource(cr)
                    .waitUntilCondition(r -> isDesiredState(r, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling)
                                    && !Objects.equals(previousLastRequestId, getLastRequestId(r)),
                            2, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Catch later
        }
        GenericKubernetesResource resultCR = kubernetesClient.genericKubernetesResources(crd)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .get();
        assertDesiredState(resultCR, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling);
        assertNotEquals(previousLastRequestId, getLastRequestId(resultCR), "lastRequestId must change after Secret event");
    }

    private String getLastRequestId(CustomResourceDefinitionContext crd, GenericKubernetesResource cr) {
        GenericKubernetesResource currentCR = kubernetesClient.genericKubernetesResources(crd)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .get();
        return getLastRequestId(currentCR);
    }

    private String getLastRequestId(GenericKubernetesResource cr) {
        if (cr == null || cr.getAdditionalProperties() == null) {
            return null;
        }

        Map<String, Object> status =
                (Map<String, Object>) cr.getAdditionalProperties().get("status");
        if (status == null) {
            return null;
        }

        return (String) status.get("lastRequestId");
    }

    private boolean isCurrentGenerationObserved(GenericKubernetesResource cr) {
        if (cr == null || cr.getMetadata() == null || cr.getAdditionalProperties() == null) {
            return false;
        }

        Map<String, Object> status =
                (Map<String, Object>) cr.getAdditionalProperties().get("status");
        if (status == null || !(status.get("observedGeneration") instanceof Number observedGeneration)) {
            return false;
        }

        return observedGeneration.longValue() == cr.getMetadata().getGeneration();
    }

    private DatabaseResponse waitForDatabaseConnectionProperties(Map<String, Object> classifier, Map<String, Object> expected) {
        return (DatabaseResponse) Failsafe.with(DEFAULT_RETRY_POLICY).get(() -> {
            var db = helperV3.getDatabaseByClassifierAsPOJO(
                    helperV3.getClusterDbaAuthorization(),
                    classifier,
                    NAMESPACE,
                    "postgresql",
                    200);
            assertConnectionPropertiesContains(db, expected);
            return db;
        });
    }

    private void waitForDatabaseNotFound(Map<String, Object> classifier) {
        Failsafe.with(DEFAULT_RETRY_POLICY).run(() ->
                helperV3.getDatabaseByClassifierAsPOJO(
                        helperV3.getClusterDbaAuthorization(),
                        classifier,
                        NAMESPACE,
                        "postgresql",
                        404));
    }

    private DatabaseResponse waitForDatabaseConnectionProperties(Map<String, Object> classifier, String role, Map<String, Object> expected) {
        return (DatabaseResponse) Failsafe.with(DEFAULT_RETRY_POLICY).get(() -> {
            var db = helperV3.getDatabaseByClassifierAsPOJO(
                    helperV3.getClusterDbaAuthorization(),
                    classifier,
                    NAMESPACE,
                    "postgresql",
                    200,
                    role);
            assertConnectionPropertiesContains(db, expected);
            return db;
        });
    }

    private void assertConnectionPropertiesContains(DatabaseResponse db, Map<String, Object> expected) {
        expected.forEach((key, value) ->
                assertEquals(value, db.getConnectionProperties().get(key), "connection property '" + key + "'"));
    }

    private static void dbaasOperatorExistOrSkipTests() {
        var pods = kubernetesClient.pods().withLabel("name", DBAAS_OPERATOR_NAME).list().getItems();
        assumeTrue(pods != null && !pods.isEmpty(), "dbaas-operator do not exists, 'OperatorIT' tests will be ignored");
    }

    private static void createNamespaceBindingCROrSkipTests() {
        try {
            // The operator deploy holds no Secret RBAC, so grant it Secret access in this namespace
            // via a Role + RoleBinding — provisioned alongside the NamespaceBinding, exactly as a
            // real namespace onboarding would (see config/samples/namespaced-secret-rbac.yaml).
            createOperatorSecretRbac();

            var cr = buildNamespaceBindingCR();
            kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                    .inNamespace(NAMESPACE)
                    .resource(cr)
                    .create();

            kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                    .inNamespace(NAMESPACE)
                    .resource(cr)
                    .waitUntilCondition(r -> r.getMetadata().getFinalizers().contains("platform.dbaas.netcracker.com/binding-protection"), 1, TimeUnit.MINUTES);
        } catch (Exception ex) {
            throw new TestAbortedException("Failed to create CR 'NamespaceBinding', tests aborted");
        }
    }

    // Grants the operator (dbaas-operator ServiceAccount) namespaced Secret access in NAMESPACE.
    // Mirrors production, where each namespace's onboarding provisions this Role + RoleBinding
    // next to the NamespaceBinding; the operator deploy itself carries no Secret RBAC.
    private static void createOperatorSecretRbac() {
        var role = new RoleBuilder()
                .withNewMetadata()
                .withName(OPERATOR_SECRET_RBAC_NAME).withNamespace(NAMESPACE)
                .addToLabels(TEST_ID, TEST_ID)
                .endMetadata()
                .addNewRule()
                .withApiGroups("")
                .withResources("secrets")
                .withVerbs("get", "create", "update", "patch")
                .endRule()
                .build();
        kubernetesClient.rbac().roles().inNamespace(NAMESPACE).resource(role).create();

        var roleBinding = new RoleBindingBuilder()
                .withNewMetadata()
                .withName(OPERATOR_SECRET_RBAC_NAME).withNamespace(NAMESPACE)
                .addToLabels(TEST_ID, TEST_ID)
                .endMetadata()
                .withNewRoleRef()
                .withApiGroup("rbac.authorization.k8s.io").withKind("Role").withName(OPERATOR_SECRET_RBAC_NAME)
                .endRoleRef()
                .addNewSubject()
                .withKind("ServiceAccount").withName(OPERATOR_SERVICE_ACCOUNT).withNamespace(NAMESPACE)
                .endSubject()
                .build();
        kubernetesClient.rbac().roleBindings().inNamespace(NAMESPACE).resource(roleBinding).create();
    }

    private static void deleteAllLogicalDatabases() throws IOException {
        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces(NAMESPACE_PATTERN);
        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces(TEST_NAMESPACE_PATTERN);

        List<DatabaseV3> logicalDbs;
        do {
            // limit 20 DBs, let's repeat the check to be sure that we delete all DBs
            logicalDbs = helperV3.findLogicalDatabasesByNamespaces(List.of(NAMESPACE)).stream()
                    .filter(db -> NAMESPACE_PATTERN.matcher((String) db.getClassifier().get("microserviceName")).matches())
                    .toList();

            for (DatabaseV3 logicalDb : logicalDbs) {
                deleteDb(logicalDb.getClassifier(), logicalDb.getType());
            }
        } while (!logicalDbs.isEmpty());
    }

    private static void deleteDb(Map<String, Object> classifier, String type) throws IOException {
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
        classifierWithRolesRequest.setOriginService((String) classifier.get("microserviceName"));

        helperV3.deleteDatabasesByClassifierRequest((String) classifier.get("namespace"), type, classifierWithRolesRequest, 200);
    }

    // Deletes a balancing-rule singleton CR by name and waits until it is gone.
    // The finalizer-driven cleanup removes the rule from the aggregator, so this
    // also resets aggregator state between tests. The exception is swallowed: a
    // missing CR (already absent) is the desired end state.
    private void deleteBalancingRuleAndWait(CustomResourceDefinitionContext crd, String name) {
        try {
            kubernetesClient.genericKubernetesResources(crd)
                    .inNamespace(NAMESPACE)
                    .withName(name)
                    .delete();
            kubernetesClient.genericKubernetesResources(crd)
                    .inNamespace(NAMESPACE)
                    .withName(name)
                    .waitUntilCondition(Objects::isNull, 2, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.info("Balancing rule '{}' cleanup: {}", name, e.toString());
        }
    }

    private List<PerMicroserviceRuleDTO> getOnMicroserviceRules() throws IOException {
        Request request = helperV3.createRequest(
                String.format("/api/v3/dbaas/%s/physical_databases/rules/onMicroservices", NAMESPACE),
                helperV3.getClusterDbaAuthorization(), null, "GET");
        try (Response response = helperV3.executeRequest(request, 200)) {
            return helperV3.objectMapper.readValue(response.body().string(),
                    new TypeReference<List<PerMicroserviceRuleDTO>>() {
                    });
        }
    }

    private List<PermanentPerNamespaceRuleDTO> getPermanentRules(String namespace) throws IOException {
        Request request = helperV3.createRequest(
                String.format("/api/v3/dbaas/balancing/rules/permanent?namespace=%s", namespace),
                helperV3.getClusterDbaAuthorization(), null, "GET");
        try (Response response = helperV3.executeRequest(request, 200)) {
            return helperV3.objectMapper.readValue(response.body().string(),
                    new TypeReference<List<PermanentPerNamespaceRuleDTO>>() {
                    });
        }
    }

    private Map<String, DebugRulesDbTypeData> debugRulesForMicroservice(String microserviceName) {
        DebugRulesRequest debugRulesRequest = new DebugRulesRequest();
        debugRulesRequest.setRules(List.of());
        debugRulesRequest.setMicroservices(List.of(microserviceName));
        Request request = helperV3.createRequest(
                String.format("api/v3/dbaas/%s/physical_databases/rules/debug", NAMESPACE),
                helperV3.getClusterDbaAuthorization(), debugRulesRequest, "POST");
        Map result = helperV3.executeRequest(request, Map.class, 200);
        Map<String, Map<String, DebugRulesDbTypeData>> debugResponseData =
                helperV3.objectMapper.convertValue(result, new TypeReference<>() {
                });
        Map<String, DebugRulesDbTypeData> microserviceData = debugResponseData.get(microserviceName);
        assertNotNull(microserviceData);
        return microserviceData;
    }

    private String getAnyRegisteredPhysDbId(String dbType) throws IOException {
        var databases = helperV3.getRegisteredPhysicalDatabases(dbType, helperV3.getClusterDbaAuthorization(), 200)
                .getIdentified();
        var physDbId = databases.keySet().stream().findFirst();
        assumeTrue(physDbId.isPresent(), "No registered physical database of type " + dbType);
        return physDbId.get();
    }
}
