package org.qubership.cloud.encryption.cipher;

import com.google.common.base.Optional;

import javax.annotation.Nonnull;
import java.security.Key;

public interface DecryptionRequest extends CryptoRequest {
    /**
     * Algorithm that should be apply for decrypt encrypted text
     * 
     * @return optional
     */
    @Nonnull
    @Override
    Optional<String> getAlgorithm();

    /**
     * Key that should be use for decrypt encrypted text
     * 
     * @return optional
     */
    @Nonnull
    @Override
    Optional<Key> getKey();

    /**
     * @return not null data that should be decrypted to plain text
     */
    @Nonnull
    byte[] getEncryptedText();
}
