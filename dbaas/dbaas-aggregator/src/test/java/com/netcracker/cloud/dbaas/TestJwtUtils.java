package com.netcracker.cloud.dbaas;

import jakarta.inject.Singleton;
import lombok.Getter;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import java.util.Collections;
import java.util.Map;

@Singleton
public class TestJwtUtils {
    @Getter
    final String defaultNamespace = "default";
    @Getter
    private final String jwtIssuer = "https://kubernetes.default.svc.cluster.local";
    @Getter
    private final String jwksEndpoint = "https://kubernetes.default.svc.cluster.local/openid/v1/jwks";
    @Getter
    private final String dbaasJwtAudience = "dbaas";
    @Getter
    private final String jwks;
    private final RsaJsonWebKey rsaJsonWebKey;

    public TestJwtUtils() throws JoseException {
        rsaJsonWebKey = RsaJwkGenerator.generateJwk(2048);
        rsaJsonWebKey.setKeyId("k1");
        jwks = new JsonWebKeySet(rsaJsonWebKey).toJson();
    }

    public String newJwt(JwtClaims claims, boolean differentKey) throws JoseException {
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

    public String newDefaultClaimsJwt(String namespace) throws JoseException {
        JwtClaims validClaims = new JwtClaims();
        validClaims.setIssuer(jwtIssuer);
        validClaims.setAudience(dbaasJwtAudience);
        validClaims.setSubject("some-service");
        validClaims.setExpirationTimeMinutesInTheFuture(10);
        validClaims.setGeneratedJwtId();
        validClaims.setIssuedAtToNow();
        validClaims.setClaim("kubernetes.io", Map.of("namespace", namespace, "serviceaccount", Collections.singletonMap("name", "default")));

        return newJwt(validClaims, false);
    }
}
