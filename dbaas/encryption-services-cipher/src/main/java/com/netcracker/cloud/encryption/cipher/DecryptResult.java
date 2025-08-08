package org.qubership.cloud.encryption.cipher;

import javax.annotation.Nonnull;

/**
 * Result container for decrypt message
 */
public interface DecryptResult {
    /**
     * @return result as is
     */
    @Nonnull
    byte[] getResultAsByteArray();

    /**
     * @return string from byte array {@link DecryptResult#getResultAsByteArray()} with UTF-8 encoding
     */
    @Nonnull
    String getResultAsString();
}
