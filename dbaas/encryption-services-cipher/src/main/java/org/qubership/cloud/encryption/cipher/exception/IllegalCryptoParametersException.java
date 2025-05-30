package org.qubership.cloud.encryption.cipher.exception;

@SuppressWarnings("serial")
public class IllegalCryptoParametersException extends CryptoException {
    public IllegalCryptoParametersException(String message) {
        super(message);
    }

    public IllegalCryptoParametersException(String message, Throwable cause) {
        super(message, cause);
    }

    public IllegalCryptoParametersException(Throwable cause) {
        super(cause);
    }
}
