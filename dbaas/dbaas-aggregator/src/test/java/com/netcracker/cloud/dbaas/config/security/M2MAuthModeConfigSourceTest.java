package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M2MAuthModeConfigSourceTest {

    @ParameterizedTest
    @CsvSource({"LEGACY, false", "HYBRID, true", "K8S, true"})
    void setsM2MEnabledPropertyFromMode(M2MAuthMode mode, String expected) {
        assertEquals(expected, new M2MAuthModeConfigSource(mode).getValue("dbaas.security.k8s.m2m.enabled"));
    }
}
