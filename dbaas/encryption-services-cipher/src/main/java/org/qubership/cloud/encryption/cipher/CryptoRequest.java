package org.qubership.cloud.encryption.cipher;

import com.google.common.base.Optional;

import javax.annotation.Nonnull;
import java.security.Key;

public interface CryptoRequest {
    /**
     * Algorithm that should be apply for encryption or decription
     * 
     * @return optional
     */
    @Nonnull
    Optional<String> getAlgorithm();

    /**
     * JCA Provider that should be use for encrypt or decrypt
     * 
     * @return optional
     */
    @Nonnull
    Optional<String> getProvider();

    /**
     * <p>
     * Key that should be use for encrypt or decrypt
     * </p>
     *
     * <b>Note:</b> It method return opposite value for {@link CryptoRequest#getKeyAlias()} and current method have
     * higher priority for use, so, in case when specified explicitly {@link Key} and keyAlias should be use explicit
     * {@link Key}
     * 
     * @return optional
     */
    @Nonnull
    Optional<Key> getKey();

    /**
     * <p>
     * Unique key alias that can be find in {@link org.qubership.cloud.encryption.key.KeyStore}
     * </p>
     * <b>Note:</b> It method return opposite value for {@link CryptoRequest#getKey()} but
     * {@link CryptoRequest#getKey()} have higher priority for use, so, in case when specified explicitly {@link Key}
     * and keyAlias should be use explicit {@link Key}
     * 
     * @return optional
     */
    @Nonnull
    Optional<String> getKeyAlias();

    /**
     * Initialized vector(salt) that should be use during encrypt/decrypt
     * 
     * @return optional
     */
    @Nonnull
    Optional<byte[]> getIV();
}

