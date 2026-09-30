package com.netcracker.cloud.dbaas.utils;

import java.util.Map;
import java.util.Objects;

import static com.netcracker.cloud.dbaas.Constants.*;

public final class ClassifierValidator {

    private ClassifierValidator() {
    }

    /**
     * Returns {@code true} when the classifier satisfies the V3 contract:
     * {@code microserviceName}, {@code namespace}, and {@code scope} are present,
     * and {@code scope} is either {@value com.netcracker.cloud.dbaas.Constants#SCOPE_VALUE_SERVICE}
     * or {@value com.netcracker.cloud.dbaas.Constants#SCOPE_VALUE_TENANT} (the latter requires {@code tenantId}).
     */
    public static boolean isValid(Map<String, Object> classifier) {
        if (!isValidDeclarative(classifier) || !classifier.containsKey(NAMESPACE)) {
            return false;
        }
        // scope is guaranteed SERVICE or TENANT here; TENANT additionally requires tenantId
        return Objects.equals(classifier.get(SCOPE), SCOPE_VALUE_SERVICE)
                || classifier.containsKey(TENANT_ID);
    }

    /**
     * Returns {@code true} when the classifier satisfies the declarative-flow contract:
     * {@code microserviceName} is present and {@code scope} is either
     * {@value com.netcracker.cloud.dbaas.Constants#SCOPE_VALUE_SERVICE} or
     * {@value com.netcracker.cloud.dbaas.Constants#SCOPE_VALUE_TENANT}.
     *
     * <p>Unlike {@link #isValid}, this method does not require {@code namespace} (added later by DBaaS itself)
     * or {@code tenantId} (resolved dynamically from existing databases at runtime).
     */
    public static boolean isValidDeclarative(Map<String, Object> classifier) {
        if (classifier == null || !classifier.containsKey(MICROSERVICE_NAME)) {
            return false;
        }
        Object scope = classifier.get(SCOPE);
        return Objects.equals(scope, SCOPE_VALUE_SERVICE) || Objects.equals(scope, SCOPE_VALUE_TENANT);
    }
}
