package org.qubership.cloud.dbaas.security;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sTokenWatcherTest {
    private final static RetryPolicy<Object> TOKEN_CACHE_UPDATED_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofMillis(50)).withMaxDuration(Duration.ofSeconds(1));

    K8sTokenWatcher watcher;
    AtomicReference<String> tokenCache;

    String tempdir;
    Path tokenFile;
    Path dataLink;
    Thread watcherThread;

    private static String getNewJwt() throws JoseException {
        RsaJsonWebKey rsaJsonWebKey = RsaJwkGenerator.generateJwk(2048);
        rsaJsonWebKey.setKeyId("k1");

        JwtClaims claims = new JwtClaims();
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setIssuedAtToNow();

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(rsaJsonWebKey.getPrivateKey());
        jws.setKeyIdHeaderValue(rsaJsonWebKey.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    @AfterEach
    void tearDown() {
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    @Test
    void run(@TempDir Path dir) throws IOException, JoseException {
        tempdir = dir.toAbsolutePath().toString();

        tokenFile = Files.createFile(Path.of(tempdir + "/token"));
        String oldJwt = getNewJwt();
        Files.writeString(tokenFile, oldJwt);

        dataLink = Files.createSymbolicLink(Path.of(tempdir + "/..data"), tokenFile);

        tokenCache = new AtomicReference<>("");

        watcher = new K8sTokenWatcher(tempdir, tokenFile.toAbsolutePath().toString(), tokenCache);

        watcherThread = Thread.startVirtualThread(watcher);

        Failsafe.with(TOKEN_CACHE_UPDATED_RETRY_POLICY).run(() -> {
            assertEquals(oldJwt, tokenCache.get());
        });

        String newJwt = getNewJwt();
        Files.writeString(tokenFile, newJwt);

        Files.delete(dataLink);
        Files.createSymbolicLink(dataLink, tokenFile);

        Failsafe.with(TOKEN_CACHE_UPDATED_RETRY_POLICY).run(() -> {
            assertEquals(newJwt, tokenCache.get());
        });
    }
}
