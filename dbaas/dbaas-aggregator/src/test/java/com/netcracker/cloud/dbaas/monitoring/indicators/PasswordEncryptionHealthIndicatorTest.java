package com.netcracker.cloud.dbaas.monitoring.indicators;

import com.netcracker.cloud.dbaas.service.PasswordCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PasswordEncryptionHealthIndicatorTest {

    private static final String ERROR_DETAILS = "java.lang.IllegalStateException:"
            + "PasswordEncryption does not work. See logs for details.";

    @Mock
    private PasswordCipher passwordCipher;

    @Test
    void testCheck_shouldReportUp_whenValueDecryptsBackToItself() {
        when(passwordCipher.encrypt(anyString())).thenAnswer(call -> "envelope:" + call.getArgument(0));
        when(passwordCipher.decrypt(anyString()))
                .thenAnswer(call -> ((String) call.getArgument(0)).substring("envelope:".length()));

        HealthCheckResponse response = new PasswordEncryptionHealthIndicator(passwordCipher).check();

        assertEquals(HealthStatus.UP, response.getStatus());
    }

    @Test
    void testCheck_shouldReportDown_whenTheCipherFails() {
        when(passwordCipher.encrypt(anyString())).thenThrow(new IllegalStateException("No key is configured"));

        HealthCheckResponse response = new PasswordEncryptionHealthIndicator(passwordCipher).check();

        assertEquals(HealthStatus.DOWN, response.getStatus());
        assertEquals(ERROR_DETAILS, response.getDetails().get("error"));
    }
}
