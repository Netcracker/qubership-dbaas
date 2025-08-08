package org.qubership.cloud.dbaas.config.security;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.cloud.dbaas.dto.oidc.OidcConfig;
import org.qubership.cloud.dbaas.rest.K8sOidcRestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class K8sJWTCallerPrincipalFactoryTest {
    private static final String jwtIssuer = "https://kubernetes.default.svc.cluster.local";
    private static final String jwksEndpoint = "https://kubernetes.default.svc.cluster.local/openid/v1/jwks";
    private static final String dbaasJwtAudience = "dbaas";

    @Mock
    K8sOidcRestClient restClient;

    K8sJWTCallerPrincipalFactory parser;

    private RsaJsonWebKey rsaJsonWebKey;

    @BeforeEach
    void setUp() throws Exception {
        rsaJsonWebKey = RsaJwkGenerator.generateJwk(2048);
        rsaJsonWebKey.setKeyId("k1");

        when(restClient.getOidcConfiguration(jwtIssuer)).thenReturn(new OidcConfig(jwksEndpoint));
        when(restClient.getJwks(jwksEndpoint)).thenReturn(new JsonWebKeySet(rsaJsonWebKey).toJson());

        parser = new K8sJWTCallerPrincipalFactory(jwtIssuer, dbaasJwtAudience, restClient);
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void parse() {
        JwtClaims validClaims = new JwtClaims();
        validClaims.setIssuer(jwtIssuer);
        validClaims.setAudience(dbaasJwtAudience);
        validClaims.setSubject("some-service");
        validClaims.setExpirationTimeMinutesInTheFuture(10);
        validClaims.setGeneratedJwtId();
        validClaims.setIssuedAtToNow();

        assertDoesNotThrow(() -> {
            parser.parse(newJwt(validClaims, false), null);
        });

        assertThrows(ParseException.class, () -> {
            parser.parse(newJwt(validClaims, true), null);
        });

        assertThrows(ParseException.class, () -> {
            String jwt = newJwt(validClaims, false);
            jwt += "tamperWithSignature";
            parser.parse(jwt, null);
        });

        assertThrows(ParseException.class, () -> {
            JwtClaims invalidClaims = new JwtClaims();
            validClaims.setIssuer("someOtherIssuer");
            validClaims.setAudience(dbaasJwtAudience);
            validClaims.setSubject("some-service");
            validClaims.setExpirationTimeMinutesInTheFuture(10);
            validClaims.setGeneratedJwtId();
            validClaims.setIssuedAtToNow();

            parser.parse(newJwt(invalidClaims, false), null);
        });

        assertThrows(ParseException.class, () -> {
            JwtClaims invalidClaims = new JwtClaims();
            validClaims.setIssuer(jwtIssuer);
            validClaims.setAudience("someOtherAudience");
            validClaims.setSubject("some-service");
            validClaims.setExpirationTimeMinutesInTheFuture(10);
            validClaims.setGeneratedJwtId();
            validClaims.setIssuedAtToNow();

            parser.parse(newJwt(invalidClaims, false), null);
        });

        assertThrows(ParseException.class, () -> {
            NumericDate invalidExpirationDate = NumericDate.now();
            invalidExpirationDate.addSeconds(-100);

            JwtClaims invalidClaims = new JwtClaims();
            validClaims.setIssuer(jwtIssuer);
            validClaims.setAudience(dbaasJwtAudience);
            validClaims.setSubject("some-service");
            validClaims.setExpirationTime(invalidExpirationDate);
            validClaims.setGeneratedJwtId();
            validClaims.setIssuedAtToNow();

            parser.parse(newJwt(invalidClaims, false), null);
        });
    }

    private String newJwt(JwtClaims claims, boolean differentKey) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();

        RsaJsonWebKey key;
        if (differentKey) {
            key = RsaJwkGenerator.generateJwk(2048);
            key.setKeyId("k1");
        } else {
            key = rsaJsonWebKey;
        }

        jws.setPayload(claims.toJson());
        jws.setKey(key.getPrivateKey());
        jws.setKeyIdHeaderValue(key.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }
}
