package com.netcracker.cloud.dbaas.security;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.netcracker.cloud.dbaas.TestJwtUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sTokenWatcherTest {
    private final static RetryPolicy<Object> TOKEN_CACHE_UPDATED_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofMillis(50)).withMaxDuration(Duration.ofSeconds(1));

    private TestJwtUtils jwtUtils;

    K8sTokenWatcher watcher;
    AtomicReference<String> tokenCache;

    String tempdir;
    Path tokenFile;
    Path dataLink;
    Thread watcherThread;

    K8sTokenWatcherTest() throws JoseException {
        jwtUtils  = new TestJwtUtils();
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

        JwtClaims claims = new JwtClaims();
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setIssuedAtToNow();
        String oldJwt = jwtUtils.newJwt(claims, false);

        Files.writeString(tokenFile, oldJwt);

        dataLink = Files.createSymbolicLink(Path.of(tempdir + "/..data"), tokenFile);

        tokenCache = new AtomicReference<>("");

        watcher = new K8sTokenWatcher(tempdir, tokenCache);

        watcherThread = Thread.startVirtualThread(watcher);

        Failsafe.with(TOKEN_CACHE_UPDATED_RETRY_POLICY).run(() -> {
            assertEquals(oldJwt, tokenCache.get());
        });

        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setIssuedAtToNow();
        String newJwt = jwtUtils.newJwt(claims, false);
        Files.writeString(tokenFile, newJwt);

        Files.delete(dataLink);
        Files.createSymbolicLink(dataLink, tokenFile);

        Failsafe.with(TOKEN_CACHE_UPDATED_RETRY_POLICY).run(() -> {
            assertEquals(newJwt, tokenCache.get());
        });
    }

    @Test
    void TestGetIssuer(@TempDir Path dir) throws IOException, JoseException {
        tempdir = dir.toAbsolutePath().toString();

        tokenFile = Files.createFile(Path.of(tempdir + "/token"));

        JwtClaims claims = new JwtClaims();
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setIssuedAtToNow();
        claims.setIssuer(jwtUtils.getJwtIssuer());
        String oldJwt = jwtUtils.newJwt(claims, false);

        Files.writeString(tokenFile, oldJwt);
        tokenCache = new AtomicReference<>("");
        watcher = new K8sTokenWatcher(tempdir, tokenCache);

        assertEquals(jwtUtils.getJwtIssuer(), watcher.getTokenIssuer());
    }
}
