package org.qubership.cloud.encryption.key.exception;

@SuppressWarnings("serial")
public class IllegalKeystoreConfigurationException extends RuntimeException {
    public IllegalKeystoreConfigurationException(String message) {
        super(message);
    }

    public IllegalKeystoreConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public IllegalKeystoreConfigurationException(Throwable cause) {
        super(cause);
    }
}
