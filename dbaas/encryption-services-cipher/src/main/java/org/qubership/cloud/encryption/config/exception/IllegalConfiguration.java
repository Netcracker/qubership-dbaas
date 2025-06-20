package org.qubership.cloud.encryption.config.exception;

@SuppressWarnings("serial")
public class IllegalConfiguration extends RuntimeException {
    public IllegalConfiguration(String message) {
        super(message);
    }

    public IllegalConfiguration(String message, Throwable cause) {
        super(message, cause);
    }

    public IllegalConfiguration(Throwable cause) {
        super(cause);
    }
}
