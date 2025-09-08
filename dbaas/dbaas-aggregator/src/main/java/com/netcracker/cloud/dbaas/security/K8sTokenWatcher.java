package com.netcracker.cloud.dbaas.security;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.TimeoutExceededException;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class K8sTokenWatcher implements Runnable {
    private static final String tokenFileLinkName = "..data";

    @Getter
    private final String tokenIssuer;
    private final String tokenDir;
    private final WatchService watchService;
    private final AtomicReference<String> tokenCache;
    private RetryPolicy<Object> retryPolicy;

    public K8sTokenWatcher(String tokenDir, AtomicReference<String> tokenCache) {
        retryPolicy = new RetryPolicy<>()
                .withMaxRetries(-1)
                .withBackoff(500, Duration.ofSeconds(600).toMillis(), ChronoUnit.MILLIS);

        this.tokenDir = tokenDir;
        this.tokenCache = tokenCache;

        try {
            if (!refreshToken()) {
                throw new RuntimeException("Failed to load Kubernetes service account token with dir %s".formatted(tokenDir));
            }

            JwtConsumer jwtConsumer = new JwtConsumerBuilder()
                    .setSkipAllValidators()
                    .setDisableRequireSignature()
                    .setSkipSignatureVerification()
                    .build();
            JwtContext jwtContext = jwtConsumer.process(tokenCache.get());
            this.tokenIssuer = jwtContext.getJwtClaims().getIssuer();;

            watchService = FileSystems.getDefault().newWatchService();

            Path path = Paths.get(this.tokenDir);
            path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException | InvalidJwtException | InterruptedException | MalformedClaimException e) {
            log.error("Failed to create K8sTokenWatcher", e);
            throw new RuntimeException(e);
        }
    }

    public void run() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE) {
                        continue;
                    }

                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    if (tokenFileLinkName.equals(ev.context().getFileName().toString())) {
                        refreshToken();
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            log.error("K8sTokenWatcher listening thread interrupted", e);
        }
    }

    private boolean refreshToken() throws InterruptedException {
        File tokenFile = new File(tokenDir+"/token");

        if (!tokenFile.exists()) {
            String msg = "Kubernetes service account token at dir %s doesn't exist".formatted(tokenDir);
            log.error(msg);
            throw new InterruptedException(msg);
        }

        if (!tokenFile.canRead()) {
            String msg = "Process doesn't have read permissions to Kubernetes service account token at dir %s".formatted(tokenDir);
            log.error(msg);
            throw new InterruptedException(msg);
        }

        try {
            Failsafe.with(retryPolicy).run(() -> {
                String tokenContents = Files.readString(tokenFile.toPath());
                tokenCache.set(tokenContents);

                Optional<Long> tokenRefreshSeconds = getTokenRefreshSeconds(tokenContents);
                if (tokenRefreshSeconds.isPresent()) {
                    retryPolicy = retryPolicy.withMaxDuration(Duration.ofSeconds(tokenRefreshSeconds.get()));
                }
            });
            return true;
        } catch (TimeoutExceededException e) {
            log.error("Reading kubernetes service account token at path %s time out exceeded. Couldn't read token", e);
            return false;
        }
    }

    private Optional<Long> getTokenRefreshSeconds(String jwtToken) {
        try {
            JwtConsumer jwtClaimsParser = new JwtConsumerBuilder()
                    .setSkipDefaultAudienceValidation()
                    .setSkipSignatureVerification()
                    .setRequireExpirationTime()
                    .setRequireIssuedAt()
                    .build();
            JwtClaims claims = jwtClaimsParser.processToClaims(jwtToken);

            return Optional.of(claims.getExpirationTime().getValue() - claims.getIssuedAt().getValue());
        } catch (InvalidJwtException | MalformedClaimException e) {
            log.error("Kubernetes service account token is invalid", e);
            return Optional.empty();
        }
    }
}
