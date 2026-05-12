package com.netcracker.it.dbaas.test.declarative;

import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.it.dbaas.entity.ClassifierWithRolesRequest;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.OperatorHelper.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class OperatorIT extends AbstractIT {

    @BeforeAll
    static void setUp() throws IOException {
        dbaasOperatorExistOrSkipTests();
        NAMESPACE = getTestNamespace();
        cleanUp();
        createNamespaceBindingCR();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        dbaasOperatorExistOrSkipTests();
        kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
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
                waitForDesiredState(cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
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
                waitForDesiredState(cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
            }

            @Test
            void testExternalDatabaseMissingSecret() throws IOException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-miss-secret", "does-not-exist");

                createCR(CRD_EXTERNAL_DATABASE, cr);
                waitForDesiredState(cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
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
                waitForDesiredState(cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
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
                waitForDesiredState(cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testExternalDatabaseMetadataClassifierNamespacesMismatch() throws IOException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildExternalDatabaseCR(crName, microserviceName, generateName(), "db-namespace-conflict", null);

                createCR(CRD_EXTERNAL_DATABASE, cr);
                waitForDesiredState(cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
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
                waitForDesiredState(cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_AGGREGATOR_REJECTED, STATUS_TRUE);

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

                kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                        .inNamespace(NAMESPACE)
                        .resource(cr)
                        .edit(r -> {
                            Map<String, Object> currSpec = (Map<String, Object>) r.getAdditionalProperties().get("spec");
                            currSpec.put("connectionProperties", updatedConnectionProperties);
                            return r;
                        });

                var updatedCR = kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                        .inNamespace(NAMESPACE)
                        .resource(cr)
                        .get();
                var updatedSpec = (Map<String, Object>) updatedCR.getAdditionalProperties().get("spec");
                assertEquals(List.of(Map.of("role", "admin", "extraProperties", Map.of(TEST_ID, TEST_ID))), updatedSpec.get("connectionProperties"));

                Failsafe.with(OPERATOR_RETRY_POLICY).run(() -> {
                    var updatedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
                    assertEquals(Map.of("role", "admin", TEST_ID, TEST_ID), updatedDb.getConnectionProperties());
                });
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
                waitForDesiredState(cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
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

    private void createCR(CustomResourceDefinitionContext crd, GenericKubernetesResource cr) {
        GenericKubernetesResource r = kubernetesClient.genericKubernetesResources(crd)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .create();
        log.info("CR created: {}", r);
    }

    private void waitForDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling) {
        try {
            kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                    .inNamespace(NAMESPACE)
                    .resource(cr)
                    .waitUntilCondition(r -> isDesiredState(r, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling),
                            10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Catch later
        }
        GenericKubernetesResource externalDatabaseCR = kubernetesClient.genericKubernetesResources(CRD_EXTERNAL_DATABASE)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .get();
        assertDesiredState(externalDatabaseCR, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling);
    }

    private static void dbaasOperatorExistOrSkipTests() {
        var pods = kubernetesClient.pods().withLabel("name", DBAAS_OPERATOR_NAME).list().getItems();
        assumeTrue(pods != null && !pods.isEmpty(), () -> {
            String message = "pod 'dbaas-operator' do not exists, 'OperatorIT' tests will be ignored";
            log.warn(message);
            return message;
        });
    }

    private static void createNamespaceBindingCR() {
        var cr = buildNamespaceBindingCR();
        kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .create();

        kubernetesClient.genericKubernetesResources(CRD_NAMESPACE_BINDING)
                .inNamespace(NAMESPACE)
                .resource(cr)
                .waitUntilCondition(r -> r != null
                        && r.getMetadata() != null
                        && r.getMetadata().getFinalizers() != null
                        && r.getMetadata().getFinalizers()
                        .contains("platform.dbaas.netcracker.com/binding-protection"), 1, TimeUnit.MINUTES);
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
