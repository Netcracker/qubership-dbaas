package org.qubership.cloud.encryption.config;

import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;

import javax.annotation.Nonnull;

/**
 * Configuration for encryption-service component
 */
public interface EncryptionConfiguration {
    /**
     * Configuration for {@link java.security.KeyStore} that can be local or remote
     * 
     * @return null if key store not configure otherwise return correspond config file
     * @see java.security.KeyStore
     * @see LocalKeystoreConfig
     */
    @Nonnull
    KeystoreSubsystemConfig getKeyStoreSubsystemConfig();

    /**
     * Configuration for encryptin/decryption
     * 
     * @return not null configuration
     */
    @Nonnull
    CryptoSubsystemConfig getCryptoSubsystemConfig();


}
