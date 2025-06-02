package org.qubership.cloud.encryption.cipher;

import com.google.common.base.Optional;

import javax.annotation.Nonnull;
import java.security.Key;

public interface EncryptionRequest extends CryptoRequest {
    /**
     * Plain test that should be encrypted
     * 
     * @return not null byte array for plain text
     */
    @Nonnull
    byte[] getPlainText();

    /**
     * Algorithm that should be apply for encryption
     * 
     * @return optional
     */
    @Nonnull
    @Override
    Optional<String> getAlgorithm();

    /**
     * Key that should be use for encrypt plain text
     *
     * <b>Note:</b> It method return opposite value for {@link EncryptionRequest#getKeyAlias()} and current method have
     * higher priority for use, so, in case when specified explicitly {@link Key} and keyAlias should be use explicit
     * {@link Key}
     * 
     * @return optional
     */
    @Nonnull
    @Override
    Optional<Key> getKey();

    /**
     * <p>
     * Unique key alias that can be find in {@link org.qubership.cloud.encryption.key.KeyStore} and should be use
     * encrypt plain text
     * </p>
     * <b>Note:</b> It method return opposite value for {@link EncryptionRequest#getKey()} but
     * {@link EncryptionRequest#getKey()} have higher priority for use, so, in case when specified explicitly
     * {@link Key} and keyAlias should be use explicit {@link Key}
     * 
     * @return optional
     */
    @Nonnull
    Optional<String> getKeyAlias();
}
