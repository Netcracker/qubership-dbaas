package org.qubership.cloud.encryption.config.crypto;

import com.google.common.base.Optional;

import javax.annotation.Nonnull;

public interface CryptoSubsystemConfig {
    /**
     * JCA algorithm that should be use like default for encrypt plaintext
     * 
     * @return optional with algorithm
     */
    @Nonnull
    Optional<String> getDefaultAlgorithm();

    /**
     * Alias for SecretKey that can be lockup from {@link org.qubership.cloud.encryption.key.KeyStore} and use for
     * encryption
     * 
     * @return optional with key alias
     */
    @Nonnull
    Optional<String> getDefaultKeyAlias();

    /**
     * Unique identity by that can be find in keystore subsystem correspond KeyStore
     * 
     * @return optional with keystore unique name
     * @see CryptoSubsystemConfig#getDefaultKeyAlias()
     */
    @Nonnull
    Optional<String> getKeyStoreName();
}
