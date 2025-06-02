package org.qubership.cloud.dbaas.monitoring.indicators;

import org.qubership.cloud.dbaas.dto.Secret;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse.HealthCheckResponseBuilder;
import org.qubership.cloud.dbaas.service.DataEncryption;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;


@Singleton
@Slf4j
public class PasswordEncryptionHealthIndicator implements ProbeCheck {
    public static final String PASSWORD_HEALTH_CHECK_NAME = "passwordEncryption";

    @Inject
    private Instance<DataEncryption> dataEncryptionServices;

    private Secret testSecret = new Secret("test-encryption-service-data");

    @Override
    public HealthCheckResponse check() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.builder().name(PASSWORD_HEALTH_CHECK_NAME);
        try {
            // check if password encryption works
            for (DataEncryption encryptionService : dataEncryptionServices) {
                String encrypted = encryptionService.encrypt(testSecret);
                encryptionService.remove(encrypted);
            }
            responseBuilder.up();
        } catch (Exception e) {
            log.error("PasswordEncryption does not work", e);
            responseBuilder.down().details("error", IllegalStateException.class.getName() + ":" + "PasswordEncryption does not work. See logs for details.");
        }
        return responseBuilder.build();
    }
}
