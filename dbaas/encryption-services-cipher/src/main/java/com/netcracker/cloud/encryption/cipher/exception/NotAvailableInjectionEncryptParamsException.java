package org.qubership.cloud.encryption.cipher.exception;

@SuppressWarnings("serial")
public class NotAvailableInjectionEncryptParamsException extends CryptoException {
    public NotAvailableInjectionEncryptParamsException(String message) {
        super(message);
    }

    public NotAvailableInjectionEncryptParamsException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotAvailableInjectionEncryptParamsException(Throwable cause) {
        super(cause);
    }
}
