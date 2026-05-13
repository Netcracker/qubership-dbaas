package com.netcracker.it.dbaas.test.declarative;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.dbaas.entity.ClassifierWithRolesRequest;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.entity.Role;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.test.AbstractIT;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.OperatorHelper.*;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.CRD_DATABASE_DECLARATION;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.CRD_DB_POLICY;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.PHASE_WAITING_FOR_DEPENDENCY;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.REASON_DATABASE_PROVISIONED;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.REASON_POLICY_APPLIED;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.REASON_PROVISIONING_STARTED;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.buildDatabaseDeclarationCR;
import static com.netcracker.it.dbaas.helpers.OperatorHelper.buildDbPolicyCR;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class OperatorIT extends AbstractIT {

    @BeforeAll
    static void setUp() throws IOException {
        NAMESPACE = getTestNamespace();
        cleanUp();
        dbaasOperatorExistOrSkipTests();
        createNamespaceBindingCROrSkipTests();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_DATABASE_DECLARATION)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_DB_POLICY)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        kubernetesClient.secrets()
                .withLabel(TEST_ID, TEST_ID)
                .delete();
        deleteAllLogicalDatabases();
    }

    @Override
    protected void closePortForwardAfterTest() {
    }

    @Nested
    @EnableExtension
    @Execution(ExecutionMode.CONCURRENT)
    class CRDs {

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
            void testExternalDatabaseSecretRotation() throws IOException {
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
            void testExternalDatabaseSecretCreatedAfterEDB() throws IOException {
                String crName = generateName();
                String secretName = generateName();
                String microserviceName = generateName();
                var classifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();

                var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-secret-recovery", secretName);

                createCR(CRD_EXTERNAL_DATABASE, cr);
                waitForDesiredState(CRD_EXTERNAL_DATABASE, cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, NAMESPACE, "postgresql", 404);

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
            void testExternalDatabaseSecretDeleted() throws IOException {
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
            void testExternalDatabaseSecretSharedByMultipleEDBs() throws IOException {
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
            void testExternalDatabaseMultipleSecretsInConnectionProperties() throws IOException {
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
            void testExternalDatabaseSecretMissingKey() throws IOException {
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
            void testExternalDatabaseSecretEmptyKey() throws IOException {
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
                                    spec.put("classifier", Map.of("microserviceName", "updatedMicroservice"));

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
        class DatabaseDeclaration {

            @Test
            void testDatabaseDeclarationValidMinimalSpec() throws IOException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, NAMESPACE, "postgresql");

                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
            }

            @Test
            void testDatabaseDeclarationClassifierNamespaceMismatch() throws IOException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, generateName(), "postgresql");

                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testDatabaseDeclarationLazyAndCloneRejected() throws IOException {
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

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, NAMESPACE, "postgresql", true, extraSpec);

                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testDatabaseDeclarationCloneWithoutSourceClassifier() throws IOException {
                String crName = generateName();
                String microserviceName = generateName();

                Map<String, Object> extraSpec = Map.of(
                        "initialInstantiation", Map.of("approach", "clone")
                );

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);

                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testDatabaseDeclarationSourceClassifierMicroserviceNameMismatch() throws IOException {
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

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);

                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testDatabaseDeclarationValidCloneConfig() throws IOException, SQLException {
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

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);
                Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                Map<String, Object> cloneClassifier = (Map<String, Object>) spec.get("classifier");
                cloneClassifier.put("customKeys", Map.of(
                        TEST_ID, "clone"
                ));

                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);

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
            void testDatabaseDeclarationWaitsForCloneSource() throws IOException, SQLException {
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

                var cr = buildDatabaseDeclarationCR(crName, microserviceName, NAMESPACE, "postgresql", false, extraSpec);
                Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                Map<String, Object> cloneClassifier = (Map<String, Object>) spec.get("classifier");
                cloneClassifier.put("customKeys", Map.of(
                        TEST_ID, "clone"
                ));


                createCR(CRD_DATABASE_DECLARATION, cr);
                waitForDesiredState(CRD_DATABASE_DECLARATION, cr,
                        PHASE_WAITING_FOR_DEPENDENCY, STATUS_FALSE, REASON_PROVISIONING_STARTED, STATUS_FALSE);

                var cloneClassifierMap = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                cloneClassifierMap.put("customKeys", Map.of(TEST_ID, "clone"));
                helperV3.getDatabaseByClassifierAsPOJO(
                        helperV3.getClusterDbaAuthorization(), cloneClassifierMap, NAMESPACE, "postgresql", 404);

                var sourceClassifier = new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build();
                sourceClassifier.put("customKeys", Map.of(TEST_ID, "source"));
                var sourceDb = helperV3.createDatabase(sourceClassifier, "postgresql", 201);
                helperV3.checkConnectionPostgres(sourceDb);

                waitForDesiredState(CRD_DATABASE_DECLARATION, cr,
                        PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_PROVISIONED, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(
                        helperV3.getClusterDbaAuthorization(), cloneClassifierMap, NAMESPACE, "postgresql", 200);
            }
        }

        @Nested
        @EnableExtension
        class DbPolicy {

            @Test
            void testDbPolicyBothServicesAndPolicyEmpty() {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildDbPolicyCR(crName, microserviceName, List.of(), List.of());

                createCR(CRD_DB_POLICY, cr);
                waitForDesiredState(CRD_DB_POLICY, cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                helperV3.getAccessRoles(NAMESPACE, microserviceName, 404);
            }

            @Test
            void testDbPolicyOnlyServicesSet() {
                String crName = generateName();
                String microserviceName = generateName();

                var service = Map.of("name", "svc-a", "roles", List.of("admin"));
                var cr = buildDbPolicyCR(crName, microserviceName, List.of(service), null);

                createCR(CRD_DB_POLICY, cr);
                waitForDesiredState(CRD_DB_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

                var roles = helperV3.getAccessRoles(NAMESPACE, microserviceName, 200);
                assertTrue(roles.getPolicies() == null || roles.getPolicies().isEmpty());

                var actualServices = roles.getServices();
                assertTrue(actualServices != null && !actualServices.isEmpty());
                assertEquals(1, roles.getServices().size());

                var actualService = actualServices.getFirst();
                assertEquals(actualService.getName(), service.get("name"));
                assertEquals(actualService.getRoles(), service.get("roles"));
            }

            @Test
            void testDbPolicyOnlyPolicySet() {
                String crName = generateName();
                String microserviceName = generateName();

                var policy = Map.<String, Object>of("type", "backup", "defaultRole", "admin");
                var cr = buildDbPolicyCR(crName, microserviceName, null, List.of(policy));

                createCR(CRD_DB_POLICY, cr);
                waitForDesiredState(CRD_DB_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

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
            void testDbPolicyBothServicesAndPolicySet() {
                String crName = generateName();
                String microserviceName = generateName();

                var service = Map.of("name", "svc-a", "roles", List.of("admin"));
                var policy = Map.<String, Object>of("type", "backup", "defaultRole", "admin");
                var cr = buildDbPolicyCR(crName, microserviceName, List.of(service), List.of(policy));

                createCR(CRD_DB_POLICY, cr);
                waitForDesiredState(CRD_DB_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

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
            void testDbPolicyDisableGlobalPermissions() {
                String crName = generateName();
                String microserviceName = generateName();

                var service = Map.of("name", "svc-a", "roles", List.of("admin"));
                var cr = buildDbPolicyCR(crName, microserviceName, List.of(service), null);

                Map<String, Object> spec = (Map<String, Object>) cr.getAdditionalProperties().get("spec");
                spec.put("disableGlobalPermissions", true);

                createCR(CRD_DB_POLICY, cr);
                waitForDesiredState(CRD_DB_POLICY, cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_POLICY_APPLIED, STATUS_FALSE);

                var roles = helperV3.getAccessRoles(NAMESPACE, microserviceName, 200);
                assertEquals(Boolean.TRUE, roles.getDisableGlobalPermissions());

                var actualServices = roles.getServices();
                assertTrue(actualServices != null && !actualServices.isEmpty());
                assertEquals(1, actualServices.size());

                var actualService = actualServices.getFirst();
                assertEquals(service.get("name"), actualService.getName());
                assertEquals(service.get("roles"), actualService.getRoles());
            }
        }
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

    private void createCR(CustomResourceDefinitionContext crd, GenericKubernetesResource cr) {
        GenericKubernetesResource r = kubernetesClient.genericKubernetesResources(crd)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .create();
        log.info("CR created: {}", r);
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

    private static void deleteAllLogicalDatabases() throws IOException {
        var logicalDbs = helperV3.findLogicalDatabasesByNamespaces(List.of(NAMESPACE)).stream()
                .filter(db -> NAMESPACE_PATTERN.matcher((String) db.getClassifier().get("microserviceName")).matches())
                .toList();

        for (DatabaseV3 logicalDb : logicalDbs) {
            var classifier = logicalDb.getClassifier();
            ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
            classifierWithRolesRequest.setClassifier(classifier);
            classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
            classifierWithRolesRequest.setOriginService((String) classifier.get("microserviceName"));

            helperV3.deleteDatabasesByClassifierRequest(NAMESPACE, logicalDb.getType(), classifierWithRolesRequest, 200);
        }
    }
}
