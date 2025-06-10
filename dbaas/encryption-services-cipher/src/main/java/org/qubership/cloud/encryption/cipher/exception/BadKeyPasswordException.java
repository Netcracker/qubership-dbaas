package org.qubership.cloud.encryption.cipher.exception;


/**
 * Thrown when trying to obtain the key with the wrong password.
 */
@SuppressWarnings("serial")
public class BadKeyPasswordException extends IllegalCryptoParametersException {

    public BadKeyPasswordException(String message) {
        super(message);
    }

    public BadKeyPasswordException(String message, Throwable cause) {
        super(message, cause);
    }

    public BadKeyPasswordException(Throwable cause) {
        super(cause);
    }
}

