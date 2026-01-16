package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.KubernetesTokenVerificationException;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesTokenVerifier;
import io.smallrye.jwt.auth.principal.ParseException;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class KubernetesJWTCallerPrincipalFactoryTest {
    @Mock
    KubernetesTokenVerifier verifier;
    KubernetesJWTCallerPrincipalFactory factory;

    @Test
    void parse() throws ParseException, KubernetesTokenVerificationException {
        Mockito.when(verifier.verify("token")).thenReturn(new JwtClaims());
        Mockito.when(verifier.verify("invalidToken")).thenThrow(new KubernetesTokenVerificationException("invalid token"));
        factory = new KubernetesJWTCallerPrincipalFactory(verifier);
        assertNotNull(factory.parse("token", null));
        assertThrows(ParseException.class, () -> factory.parse("invalidToken", null));
    }
}
