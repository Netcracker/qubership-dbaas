package org.qubership.cloud.encryption.config;

import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;

import javax.annotation.Nonnull;

public interface MutableEncryptionConfiguration extends EncryptionConfiguration {
    /**
     * Configuration for encryptin/decryption
     */
    void setCryptoSubsystemConfig(@Nonnull CryptoSubsystemConfig cryptoSubsystemConfig);

    /**
     * Configuration for keystores
     */
    void setKeystoreSubsystemConfig(@Nonnull KeystoreSubsystemConfig keystoreSubsystemConfig);
}
