package com.netcracker.cloud.dbaas.monitoring.indicators;

import com.netcracker.cloud.dbaas.monitoring.indicators.HealthCheckResponse.HealthCheckResponseBuilder;
import com.netcracker.cloud.dbaas.service.PasswordCipher;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;


@Singleton
@Slf4j
public class PasswordEncryptionHealthIndicator implements ProbeCheck {
    public static final String PASSWORD_HEALTH_CHECK_NAME = "passwordEncryption";

    private static final String TEST_VALUE = "test-encryption-service-data";

    private final PasswordCipher passwordCipher;

    @Inject
    public PasswordEncryptionHealthIndicator(PasswordCipher passwordCipher) {
        this.passwordCipher = passwordCipher;
    }

    @Override
    public HealthCheckResponse check() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.builder().name(PASSWORD_HEALTH_CHECK_NAME);
        try {
            if (!TEST_VALUE.equals(passwordCipher.decrypt(passwordCipher.encrypt(TEST_VALUE)))) {
                throw new IllegalStateException("Encrypted value does not decrypt back to the original");
            }
            responseBuilder.up();
        } catch (Exception e) {
            log.error("PasswordEncryption does not work", e);
            responseBuilder.down().details("error", IllegalStateException.class.getName() + ":" + "PasswordEncryption does not work. See logs for details.");
        }
        return responseBuilder.build();
    }
}
