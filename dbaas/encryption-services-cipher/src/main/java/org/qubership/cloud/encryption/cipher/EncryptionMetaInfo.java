package org.qubership.cloud.encryption.cipher;


import com.google.common.base.Optional;
import org.qubership.cloud.encryption.key.AliasedKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides meta information about encrypted text
 */
public interface EncryptionMetaInfo {

    /**
     * @return encrypted data
     */
    @Nonnull
    byte[] getEncryptedData();

    /**
     * Algorithm that was used while encryption
     * 
     * @return algorithm name
     */
    @Nonnull
    String getAlgorithm();

    /**
     * Key that was used while encryption
     * 
     * @return {@link AliasedKey} or {@code null} if key was not found
     */
    @Nullable
    AliasedKey getKey();

    /**
     * Initialize vector that was used for encryption
     * 
     * @return initialize vector as byte array or {@code null} if it was not specified
     */
    @Nullable
    Optional<byte[]> getIV();
}

