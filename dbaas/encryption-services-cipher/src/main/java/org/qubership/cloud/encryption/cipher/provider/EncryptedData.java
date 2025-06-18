package org.qubership.cloud.encryption.cipher.provider;

import com.google.common.base.Optional;
import org.qubership.cloud.encryption.key.AliasedKey;

import javax.annotation.Nonnull;

public interface EncryptedData {
    /**
     * @return not null algorithm that was use for encrypt text
     */
    @Nonnull
    String getUsedAlgorithm();

    /**
     * @return not null secret key that was use for encrypt text
     */
    @Nonnull
    AliasedKey getUsedKey();

    /**
     * Get used in encryption initialized vector(salt)
     * 
     * @return optional
     */
    @Nonnull
    Optional<byte[]> getIV();

    /**
     * @return not null byte array with encrypted text
     */
    @Nonnull
    byte[] getEncryptedData();
}
