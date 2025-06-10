package org.qubership.cloud.encryption.cipher.exception;

@SuppressWarnings("serial")
public class NotExistsSecurityKey extends IllegalCryptoParametersException {
    public NotExistsSecurityKey(String message) {
        super(message);
    }

    public NotExistsSecurityKey(String message, Throwable cause) {
        super(message, cause);
    }

    public NotExistsSecurityKey(Throwable cause) {
        super(cause);
    }
}
