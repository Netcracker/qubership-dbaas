package org.qubership.cloud.encryption.cipher.exception;

@SuppressWarnings("serial")
public class EncryptException extends CryptoException {
    public EncryptException(String message) {
        super(message);
    }

    public EncryptException(String message, Throwable cause) {
        super(message, cause);
    }

    public EncryptException(Throwable cause) {
        super(cause);
    }
}
