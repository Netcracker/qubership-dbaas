package org.qubership.cloud.encryption.config.crypto;

import javax.annotation.Nonnull;

public interface MutableCryptoSubsystemConfig extends CryptoSubsystemConfig {
    /**
     * JCA algorithm that should be use like default for encrypt plaintext
     */
    void setDefaultAlgorithm(@Nonnull String algorithm);

    /**
     * Alias for SecretKey that can be lockup from {@link org.qubership.cloud.encryption.key.KeyStore} and use for
     * encryption
     */
    void setDefaultKeyAlias(@Nonnull String keyAlias);

    /**
     * Unique identity by that can be find in keystore subsystem correspond KeyStore
     * 
     * @see CryptoSubsystemConfig#getDefaultKeyAlias()
     */
    void setKeyStoreName(@Nonnull String keyStoreName);
}
