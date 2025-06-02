package org.qubership.cloud.encryption.config;

import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;

import javax.annotation.Nonnull;

public interface EncryptionConfigBuilder<T extends EncryptionConfigBuilder<T>> {
    /**
     * Build configuration instance with specified parameters
     * 
     * @return build instance
     */
    @Nonnull
    EncryptionConfiguration build();

    /**
     * Copy parameters from already build configuration
     * 
     * @param config not null config for copy
     * @return builder
     */
    T copyParameters(@Nonnull EncryptionConfiguration config);

    /**
     * @param cryptoSubsystemConfig not null configuration for crypto subsystem
     * @return builder
     */
    T setCryptoSubsystemConfig(@Nonnull CryptoSubsystemConfig cryptoSubsystemConfig);

    /**
     * @param keystoreSubsystemConfig not null configuration for keystores
     * @return builder
     */
    T setKeystoreSubsystemConfig(@Nonnull KeystoreSubsystemConfig keystoreSubsystemConfig);
}
