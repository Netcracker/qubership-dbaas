package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M2MAuthModeProducerTest {

    @ParameterizedTest
    @CsvSource({"LEGACY, false", "HYBRID, true", "K8S, true"})
    void kubernetesTokenEnabledFollowsMode(M2MAuthMode mode, boolean expected) {
        assertEquals(expected, M2MAuthModeProducer.isKubernetesTokenEnabled(mode));
    }
}
