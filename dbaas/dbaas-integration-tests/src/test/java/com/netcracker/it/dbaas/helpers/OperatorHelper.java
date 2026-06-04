package com.netcracker.it.dbaas.helpers;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.netcracker.it.dbaas.test.AbstractIT.getPropertyOrEnv;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class OperatorHelper {

    public static final String DBAAS_OPERATOR_NAME = "dbaas-operator";

    public static final String CR_NAMESPACE_BINDING_NAME = "binding";
    public static final String TEST_ID = "dbaas-autotest";
    public static final String CONNECTION_PROPERTIES_KEY = "connectionProperties.json";
    public static final Pattern NAMESPACE_PATTERN = Pattern.compile("^operator-autotests-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public static final String PHASE_SUCCEEDED = "Succeeded";
    public static final String PHASE_BACKING_OFF = "BackingOff";
    public static final String PHASE_INVALID_CONFIGURATION = "InvalidConfiguration";
    public static final String PHASE_WAITING_FOR_DEPENDENCY = "WaitingForDependency";
    public static final String PHASE_TERMINATED = "Terminated";

    public static final String STATUS_TRUE = "True";
    public static final String STATUS_FALSE = "False";

    public static final String REASON_DATABASE_REGISTERED = "DatabaseRegistered";
    public static final String REASON_AGGREGATOR_REJECTED = "AggregatorRejected";
    public static final String REASON_SECRET_ROTATED = "SecretRotated";
    public static final String REASON_SECRET_CREATED = "SecretCreated";
    public static final String REASON_SECRET_ERROR = "SecretError";
    public static final String REASON_SECRET_CONFLICT = "SecretConflict";
    public static final String REASON_INVALID_SPEC = "InvalidSpec";
    public static final String REASON_DATABASE_PROVISIONED = "DatabaseProvisioned";
    public static final String REASON_DATABASE_NOT_FOUND = "DatabaseNotFound";
    public static final String REASON_UNAUTHORIZED = "Unauthorized";
    public static final String REASON_AGGREGATOR_ERROR = "AggregatorError";
    public static final String REASON_OPERATION_TERMINATED = "OperationTerminated";
    public static final String REASON_POLICY_APPLIED = "PolicyApplied";
    public static final String REASON_PROVISIONING_STARTED = "ProvisioningStarted";

    public static String NAMESPACE;

    public static final CustomResourceDefinitionContext CRD_EXTERNAL_DATABASE =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("dbaas.netcracker.com")
                    .withVersion("v1")
                    .withPlural("externaldatabases")
                    .withScope("Namespaced")
                    .build();

    public static final CustomResourceDefinitionContext CRD_NAMESPACE_BINDING = new CustomResourceDefinitionContext.Builder()
            .withGroup("dbaas.netcracker.com")
            .withVersion("v1")
            .withPlural("namespacebindings")
            .withScope("Namespaced")
            .build();

    public static final CustomResourceDefinitionContext CRD_DATABASE_DECLARATION =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("dbaas.netcracker.com")
                    .withVersion("v1")
                    .withPlural("databasedeclarations")
                    .withScope("Namespaced")
                    .build();

    public static final CustomResourceDefinitionContext CRD_DB_POLICY =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("dbaas.netcracker.com")
                    .withVersion("v1")
                    .withPlural("dbpolicies")
                    .withScope("Namespaced")
                    .build();

    public static final CustomResourceDefinitionContext CRD_DATABASE_SECRET =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("dbaas.netcracker.com")
                    .withVersion("v1")
                    .withPlural("databasesecrets")
                    .withScope("Namespaced")
                    .build();

    public static String getTestNamespace() {
        String namespace = getPropertyOrEnv("clouds.cloud.namespaces.namespace");
        assumeTrue(namespace != null && !namespace.isBlank(), "Failed to get 'clouds.cloud.namespaces.namespace' property");
        return namespace;
    }

    public static GenericKubernetesResource buildNamespaceBindingCR() {
        return buildNamespaceBindingCR("binding", NAMESPACE, NAMESPACE);
    }

    public static GenericKubernetesResource buildNamespaceBindingCR(String crName, String namespace, String operatorNamespace) {
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion("dbaas.netcracker.com/v1");
        cr.setKind("NamespaceBinding");

        ObjectMeta meta = new ObjectMeta();
        meta.setName(crName);
        meta.setNamespace(namespace);
        meta.getLabels().put(TEST_ID, TEST_ID);
        cr.setMetadata(meta);

        cr.setAdditionalProperty("spec", Map.of(
                "operatorNamespace", operatorNamespace
        ));
        return cr;
    }

    public static GenericKubernetesResource buildExternalDatabaseCR(String crName, String microserviceName, String namespace, String dbName, String secretName) {
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion("dbaas.netcracker.com/v1");
        cr.setKind("ExternalDatabase");

        ObjectMeta meta = new ObjectMeta();
        meta.setName(crName);
        meta.setNamespace(NAMESPACE);
        meta.getLabels().put(TEST_ID, TEST_ID);
        cr.setMetadata(meta);

        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", microserviceName);
        classifier.put("namespace", namespace);
        classifier.put("scope", "service");

        Map<String, Object> connectionProps = new HashMap<>();
        connectionProps.put("role", "admin");
        connectionProps.put(
                "extraProperties",
                Map.of("url", "jdbc:postgresql://pg.example.com:5432/db-201")
        );

        if (secretName != null && !secretName.isBlank()) {
            connectionProps.put(
                    "credentialsSecretRef",
                    Map.of(
                            "name", secretName,
                            "keys", List.of(
                                    Map.of("key", "username", "name", "username"),
                                    Map.of("key", "password", "name", "password")
                            )
                    )
            );
        }

        Map<String, Object> specBody = new HashMap<>();
        specBody.put("classifier", classifier);
        specBody.put("type", "postgresql");
        specBody.put("dbName", dbName);
        specBody.put("connectionProperties", List.of(connectionProps));

        cr.setAdditionalProperty("spec", specBody);
        return cr;
    }

    public static boolean isDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling) {
        if (cr == null || cr.getAdditionalProperties() == null) {
            return false;
        }

        Map<String, Object> status =
                (Map<String, Object>) cr.getAdditionalProperties().get("status");

        if (status == null) {
            return false;
        }

        String actualPhase = (String) status.get("phase");
        if (!desiredPhase.equals(actualPhase)) {
            return false;
        }

        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) status.get("conditions");

        if (conditions == null) {
            return false;
        }

        Map<String, Map<String, Object>> typeToMap = conditions.stream()
                .collect(Collectors.toMap(
                        c -> (String) c.get("type"),
                        Function.identity()
                ));

        String actualReadiness = typeToMap.get("Ready").get("status").toString();
        String actualReadyReason = typeToMap.get("Ready").get("reason").toString();
        String actualStalling = typeToMap.get("Stalled").get("status").toString();

        return desiredReadiness.equals(actualReadiness) &&
                desiredReadyReason.equals(actualReadyReason) &&
                desiredStalling.equals(actualStalling);
    }

    public static void assertDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness, String desiredReadyReason, String desiredStalling) {
        log.info("Start assert for desired state cr: {}", cr);

        Map<String, Object> status =
                (Map<String, Object>) cr.getAdditionalProperties().get("status");
        assertNotNull(status, "status must be not null");

        log.info("CR status: {}", status);

        String actualPhase = (String) status.get("phase");
        assertEquals(desiredPhase, actualPhase, "actual 'Phase' do not equals to desired one");

        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) status.get("conditions");

        Map<String, Map<String, Object>> typeToMap = conditions.stream()
                .collect(Collectors.toMap(
                        c -> (String) c.get("type"),
                        Function.identity()
                ));

        assertEquals(desiredReadiness, typeToMap.get("Ready").get("status"), "actual 'Ready.Status' do not equals to desired one");
        assertEquals(desiredReadyReason, typeToMap.get("Ready").get("reason"), "actual 'Ready.Reason' do not equals to desired one");
        assertEquals(desiredStalling, typeToMap.get("Stalled").get("status"), "actual 'Stalled.Status' do not equals to desired one");
    }

    public static void assertSecretOwnedByCR(Secret secret, HasMetadata ownerCR) {
        var ownerReferences = secret.getMetadata().getOwnerReferences();

        assertNotNull(ownerReferences, "OwnerReferences must not be null");
        assertFalse(ownerReferences.isEmpty(), "OwnerReferences must not be empty");

        var ownerRef = ownerReferences.getFirst();

        assertEquals(
                ownerCR.getMetadata().getUid(),
                ownerRef.getUid(),
                "Secret must be owned by CR"
        );
    }

    public static void assertSecretContainsConnectionProperties(
            Secret secret,
            String dataKey,
            Map<String, Object> expectedProperties
    ) {
        assertNotNull(secret.getData(), "Secret data must not be null");
        assertTrue(
                secret.getData().containsKey(dataKey),
                String.format("Secret must contain key: %s", dataKey)
        );

        String decodedJson = new String(
                Base64.getDecoder().decode(secret.getData().get(dataKey)),
                StandardCharsets.UTF_8
        );

        expectedProperties.forEach((key, value) -> {
            assertTrue(
                    decodedJson.contains(key),
                    String.format("Secret connectionProperties must contain key: %s", key)
            );
            assertTrue(
                    decodedJson.contains(String.valueOf(value)),
                    String.format("Secret connectionProperties must contain value %s for key: %s", value, key)
            );
        });
    }

    public static String generateName() {
        return "operator-autotests-" + UUID.randomUUID();
    }

    public static GenericKubernetesResource buildDatabaseDeclarationCR(String crName, String microserviceName,
                                                                       String namespace, String type) {
        return buildDatabaseDeclarationCR(crName, microserviceName, namespace, type, false, new HashMap<>());
    }

    public static GenericKubernetesResource buildDatabaseDeclarationCR(String crName, String microserviceName,
                                                                       String namespace, String type, boolean lazy,
                                                                       Map<String, Object> extraSpecFields) {
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion("dbaas.netcracker.com/v1");
        cr.setKind("DatabaseDeclaration");

        ObjectMeta meta = new ObjectMeta();
        meta.setName(crName);
        meta.setNamespace(NAMESPACE);
        meta.getLabels().put(TEST_ID, TEST_ID);
        cr.setMetadata(meta);

        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", microserviceName);
        classifier.put("namespace", namespace);
        classifier.put("scope", "service");

        Map<String, Object> specBody = new HashMap<>();
        specBody.put("classifier", classifier);
        specBody.put("type", type);
        specBody.put("lazy", lazy);
        specBody.putAll(extraSpecFields);

        cr.setAdditionalProperty("spec", specBody);
        return cr;
    }

    public static GenericKubernetesResource buildDatabaseSecretCR(String crName, String originService, String microserviceName,
                                                                  String namespace, String secretName, String role,
                                                                  String type) {

        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion("dbaas.netcracker.com/v1");
        cr.setKind("DatabaseSecret");

        ObjectMeta meta = new ObjectMeta();
        meta.setName(crName);
        meta.setNamespace(NAMESPACE);
        meta.getLabels().put(TEST_ID, TEST_ID);
        meta.getLabels().put("app.kubernetes.io/name", originService);
        cr.setMetadata(meta);

        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", microserviceName);
        classifier.put("namespace", namespace);
        classifier.put("scope", "service");

        Map<String, Object> specBody = new HashMap<>();
        specBody.put("classifier", classifier);
        specBody.put("secretName", secretName);
        specBody.put("type", type);

        if (role != null && !role.isBlank()) {
            specBody.put("userRole", role);
        }

        cr.setAdditionalProperty("spec", specBody);
        return cr;
    }

    public static GenericKubernetesResource buildDbPolicyCR(String crName, String microserviceName,
                                                            List<Map<String, Object>> services,
                                                            List<Map<String, Object>> policy) {
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion("dbaas.netcracker.com/v1");
        cr.setKind("DbPolicy");

        ObjectMeta meta = new ObjectMeta();
        meta.setName(crName);
        meta.setNamespace(NAMESPACE);
        meta.getLabels().put(TEST_ID, TEST_ID);
        cr.setMetadata(meta);

        Map<String, Object> specBody = new HashMap<>();
        specBody.put("microserviceName", microserviceName);
        if (services != null) {
            specBody.put("services", services);
        }
        if (policy != null) {
            specBody.put("policy", policy);
        }

        cr.setAdditionalProperty("spec", specBody);
        return cr;
    }

    public static String extractPasswordFromSecret(Secret secret) {
        assertNotNull(secret, "secret must exist");
        String decodedJson = new String(
                Base64.getDecoder().decode(secret.getData().get(CONNECTION_PROPERTIES_KEY)),
                StandardCharsets.UTF_8
        );
        int startIdx = decodedJson.indexOf("\"password\":\"") + 12;
        int endIdx = decodedJson.indexOf("\"", startIdx);
        return decodedJson.substring(startIdx, endIdx);
    }
}
