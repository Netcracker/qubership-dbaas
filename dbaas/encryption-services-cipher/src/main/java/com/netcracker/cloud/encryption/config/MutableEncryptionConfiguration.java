package com.netcracker.cloud.encryption.config;

import com.netcracker.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import com.netcracker.cloud.encryption.config.keystore.KeystoreSubsystemConfig;

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
