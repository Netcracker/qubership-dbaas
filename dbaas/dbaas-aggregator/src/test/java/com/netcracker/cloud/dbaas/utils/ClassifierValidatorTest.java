package com.netcracker.cloud.dbaas.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.netcracker.cloud.dbaas.Constants.*;
import static com.netcracker.cloud.dbaas.utils.ClassifierValidator.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassifierValidatorTest {

    @Test
    void testIsValidDeclarative_returnsFalse_whenClassifierIsNull() {
        assertFalse(isValidDeclarative(null));
    }

    @Test
    void testIsValidDeclarative_returnsFalse_whenMicroserviceNameMissing() {
        assertFalse(isValidDeclarative(Map.of(SCOPE, SCOPE_VALUE_SERVICE)));
    }

    @Test
    void testIsValidDeclarative_returnsFalse_whenScopeKeyAbsent() {
        assertFalse(isValidDeclarative(Map.of(MICROSERVICE_NAME, "svc")));
    }

    @Test
    void testIsValidDeclarative_returnsFalse_whenScopeValueIsUnknown() {
        assertFalse(isValidDeclarative(Map.of(MICROSERVICE_NAME, "svc", SCOPE, "123321")));
    }

    @Test
    void testIsValidDeclarative_returnsTrue_whenScopeIsService() {
        assertTrue(isValidDeclarative(Map.of(MICROSERVICE_NAME, "svc", SCOPE, SCOPE_VALUE_SERVICE)));
    }

    @Test
    void testIsValidDeclarative_returnsTrue_whenScopeIsTenantWithoutTenantId() {
        // tenantId is not required: it is resolved from existing databases at runtime
        assertTrue(isValidDeclarative(Map.of(MICROSERVICE_NAME, "svc", SCOPE, SCOPE_VALUE_TENANT)));
    }

    @Test
    void testIsValidDeclarative_returnsTrue_whenScopeIsTenantWithTenantId() {
        assertTrue(isValidDeclarative(Map.of(MICROSERVICE_NAME, "svc", SCOPE, SCOPE_VALUE_TENANT, TENANT_ID, "t1")));
    }


    @Test
    void testIsValid_returnsFalse_whenClassifierIsNull() {
        assertFalse(isValid(null));
    }

    @Test
    void testIsValid_returnsFalse_whenMicroserviceNameMissing() {
        assertFalse(isValid(Map.of(NAMESPACE, "ns", SCOPE, SCOPE_VALUE_SERVICE)));
    }

    @Test
    void testIsValid_returnsFalse_whenNamespaceMissing() {
        assertFalse(isValid(Map.of(MICROSERVICE_NAME, "svc", SCOPE, SCOPE_VALUE_SERVICE)));
    }

    @Test
    void testIsValid_returnsFalse_whenScopeKeyAbsent() {
        assertFalse(isValid(Map.of(MICROSERVICE_NAME, "svc", NAMESPACE, "ns")));
    }

    @Test
    void testIsValid_returnsFalse_whenScopeValueIsUnknown() {
        assertFalse(isValid(Map.of(MICROSERVICE_NAME, "svc", NAMESPACE, "ns", SCOPE, "123321")));
    }

    @Test
    void testIsValid_returnsTrue_whenScopeIsService() {
        assertTrue(isValid(Map.of(MICROSERVICE_NAME, "svc", NAMESPACE, "ns", SCOPE, SCOPE_VALUE_SERVICE)));
    }

    @Test
    void testIsValid_returnsFalse_whenScopeIsTenantAndTenantIdMissing() {
        assertFalse(isValid(Map.of(MICROSERVICE_NAME, "svc", NAMESPACE, "ns", SCOPE, SCOPE_VALUE_TENANT)));
    }

    @Test
    void testIsValid_returnsTrue_whenScopeIsTenantAndTenantIdPresent() {
        assertTrue(isValid(Map.of(MICROSERVICE_NAME, "svc", NAMESPACE, "ns", SCOPE, SCOPE_VALUE_TENANT, TENANT_ID, "t1")));
    }
}
