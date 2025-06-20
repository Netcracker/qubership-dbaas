package org.qubership.cloud.encryption.cipher.exception;

@SuppressWarnings("serial")
public class DecryptException extends CryptoException {
    public DecryptException(String message) {
        super(message);
    }

    public DecryptException(String message, Throwable cause) {
        super(message, cause);
    }

    public DecryptException(Throwable cause) {
        super(cause);
    }
}
