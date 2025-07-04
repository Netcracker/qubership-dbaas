package org.qubership.cloud.encryption.config.keystore.type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface LocalKeystoreConfigBuilder extends KeystoreConfigBuilder<LocalKeystoreConfigBuilder> {
    /**
     * @return create new instance
     */
    @Nonnull
    LocalKeystoreConfig build();

    /**
     * @param config not null config where need copy parameters
     * @return builder
     */
    @Nonnull
    LocalKeystoreConfigBuilder copyParameters(LocalKeystoreConfig config);

    /**
     * Set Absolute path where locate to keystore file
     * 
     * @see java.security.KeyStore
     */
    LocalKeystoreConfigBuilder setLocation(@Nonnull String keyStoreLocation);

    /**
     * Type Keystore. For example JSK
     */
    LocalKeystoreConfigBuilder setKeystoreType(@Nonnull String type);

    /**
     * @param password password for unlock keystore or null if keystore can be open without password
     */
    LocalKeystoreConfigBuilder setPassword(@Nullable String password);

    /**
     * @param deprecated {@code true} if keystore is deprecated
     */
    LocalKeystoreConfigBuilder setDeprecated(boolean deprecated);
}

