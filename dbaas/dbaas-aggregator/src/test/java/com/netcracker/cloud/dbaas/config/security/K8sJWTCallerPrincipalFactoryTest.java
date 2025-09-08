package com.netcracker.cloud.dbaas.config.security;

import io.smallrye.jwt.auth.principal.ParseException;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.netcracker.cloud.dbaas.TestJwtUtils;
import com.netcracker.cloud.dbaas.dto.oidc.OidcConfig;
import com.netcracker.cloud.dbaas.rest.K8sOidcRestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class K8sJWTCallerPrincipalFactoryTest {
    @Mock
    K8sOidcRestClient restClient;
    K8sJWTCallerPrincipalFactory parser;

    TestJwtUtils jwtUtils;

    public K8sJWTCallerPrincipalFactoryTest() throws JoseException {
        jwtUtils = new TestJwtUtils();
    }

    @BeforeEach
    void setUp() throws Exception {
        when(restClient.getOidcConfiguration()).thenReturn(new OidcConfig(jwtUtils.getJwksEndpoint()));
        when(restClient.getJwks(jwtUtils.getJwksEndpoint())).thenReturn(jwtUtils.getJwks());

        parser = new K8sJWTCallerPrincipalFactory(true, jwtUtils.getDbaasJwtAudience(), restClient);
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void parse() {
        JwtClaims validClaims = new JwtClaims();
        validClaims.setIssuer(jwtUtils.getJwtIssuer());
        validClaims.setAudience(jwtUtils.getDbaasJwtAudience());
        validClaims.setSubject("some-service");
        validClaims.setExpirationTimeMinutesInTheFuture(10);
        validClaims.setGeneratedJwtId();
        validClaims.setIssuedAtToNow();

        assertDoesNotThrow(() -> {
            parser.parse(jwtUtils.newJwt(validClaims, false), null);
        });

        assertThrows(ParseException.class, () -> {
            parser.parse(jwtUtils.newJwt(validClaims, true), null);
        });

        assertThrows(ParseException.class, () -> {
            String jwt = jwtUtils.newJwt(validClaims, false);
            jwt += "tamperWithSignature";
            parser.parse(jwt, null);
        });

        assertThrows(ParseException.class, () -> {
            JwtClaims invalidClaims = new JwtClaims();
            validClaims.setIssuer("someOtherIssuer");
            validClaims.setAudience(jwtUtils.getDbaasJwtAudience());
            validClaims.setSubject("some-service");
            validClaims.setExpirationTimeMinutesInTheFuture(10);
            validClaims.setGeneratedJwtId();
            validClaims.setIssuedAtToNow();

            parser.parse(jwtUtils.newJwt(invalidClaims, false), null);
        });

        assertThrows(ParseException.class, () -> {
            JwtClaims invalidClaims = new JwtClaims();
            validClaims.setIssuer(jwtUtils.getJwtIssuer());
            validClaims.setAudience("someOtherAudience");
            validClaims.setSubject("some-service");
            validClaims.setExpirationTimeMinutesInTheFuture(10);
            validClaims.setGeneratedJwtId();
            validClaims.setIssuedAtToNow();

            parser.parse(jwtUtils.newJwt(invalidClaims, false), null);
        });

        assertThrows(ParseException.class, () -> {
            NumericDate invalidExpirationDate = NumericDate.now();
            invalidExpirationDate.addSeconds(-100);

            JwtClaims invalidClaims = new JwtClaims();
            validClaims.setIssuer(jwtUtils.getJwtIssuer());
            validClaims.setAudience(jwtUtils.getDbaasJwtAudience());
            validClaims.setSubject("some-service");
            validClaims.setExpirationTime(invalidExpirationDate);
            validClaims.setGeneratedJwtId();
            validClaims.setIssuedAtToNow();

            parser.parse(jwtUtils.newJwt(invalidClaims, false), null);
        });
    }
}
