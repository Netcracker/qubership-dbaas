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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opentest4j.TestAbortedException;

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
    static void setUp() {
        dbaasOperatorExistOrSkipTests();
        createNamespaceBindingCrOrSkipTests();
    }

    @AfterAll
    static void cleanUp() throws IOException {
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
            void testExternalDatabaseCreatedSuccessfully() throws InterruptedException, IOException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "new-db", "");

                createCR(CRD_EXTERNAL_DATABASE, cr);
                waitForDesiredState(cr, PHASE_SUCCEEDED, STATUS_TRUE, REASON_DATABASE_REGISTERED, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 200);
            }

            @Test
            void testExternalDatabaseCreatedWithSecret() throws InterruptedException, IOException {
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
            void testExternalDatabaseMissingSecret() throws IOException, InterruptedException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildExternalDatabaseCR(crName, microserviceName, NAMESPACE, "db-miss-secret", "does-not-exist");

                createCR(CRD_EXTERNAL_DATABASE, cr);
                waitForDesiredState(cr, PHASE_BACKING_OFF, STATUS_FALSE, REASON_SECRET_ERROR, STATUS_FALSE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testExternalDatabaseSecretMissingKey() throws InterruptedException, IOException {
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
            void testExternalDatabaseSecretEmptyKey() throws InterruptedException, IOException {
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
            void testExternalDatabaseMetadataClassifierNamespacesMismatch() throws InterruptedException, IOException {
                String crName = generateName();
                String microserviceName = generateName();

                var cr = buildExternalDatabaseCR(crName, microserviceName, generateName(), "db-namespace-conflict", null);

                createCR(CRD_EXTERNAL_DATABASE, cr);
                waitForDesiredState(cr, PHASE_INVALID_CONFIGURATION, STATUS_FALSE, REASON_INVALID_SPEC, STATUS_TRUE);
                helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), new ClassifierBuilder().ms(microserviceName).ns(NAMESPACE).build(), NAMESPACE, "postgresql", 404);
            }

            @Test
            void testExternalDatabaseCreateNotUniqueClassifierDb() throws InterruptedException, IOException {
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

    private void waitForDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling) throws InterruptedException {
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
        var pod = kubernetesClient.pods().withLabel("name", DBAAS_OPERATOR_NAME).list().getItems();
        assumeTrue(pod != null && !pod.isEmpty(), "dbaas-operator do not exists, Operator tests will be ignored");
    }

    private static void createNamespaceBindingCrOrSkipTests() {
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
            throw new TestAbortedException("NamespaceBinding CR couldn't created, tests aborted");
        }
    }

    private static void deleteAllLogicalDatabases() throws IOException {
        var logicalDbs = helperV3.findLogicalDatabasesByNamespaces(List.of(NAMESPACE)).stream()
                .filter(db -> NAMESPACE_PATTERN.matcher((String) db.getClassifier().get("microserviceName")).matches())
                .toList();

        for (DatabaseV3 logicalDb: logicalDbs) {
            var classifier = logicalDb.getClassifier();
            ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
            classifierWithRolesRequest.setClassifier(classifier);
            classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
            classifierWithRolesRequest.setOriginService((String) classifier.get("microserviceName"));

            helperV3.deleteDatabasesByClassifierRequest(NAMESPACE, logicalDb.getType(), classifierWithRolesRequest, 200);
        }
    }
}
