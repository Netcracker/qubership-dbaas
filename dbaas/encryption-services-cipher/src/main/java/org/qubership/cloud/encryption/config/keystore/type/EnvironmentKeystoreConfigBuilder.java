package org.qubership.cloud.encryption.config.keystore.type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface EnvironmentKeystoreConfigBuilder extends KeystoreConfigBuilder<EnvironmentKeystoreConfigBuilder> {
    /**
     * @return create new instance
     */
    @Nonnull
    EnvironmentKeystoreConfig build();

    /**
     * @param config not null config where need copy parameters
     * @return builder
     */
    @Nonnull
    EnvironmentKeystoreConfigBuilder copyParameters(EnvironmentKeystoreConfig config);

    /**
     * Set prefix of key's environment variables.
     * 
     * @param prefix prefix
     */
    EnvironmentKeystoreConfigBuilder setPrefix(@Nonnull String prefix);

    /**
     * Set encryption flag of key's environment variables.
     * 
     * @param encrypted encrypted
     */
    EnvironmentKeystoreConfigBuilder setEncrypted(@Nonnull boolean encrypted);

    /**
     * @param password password
     */
    EnvironmentKeystoreConfigBuilder setPasswordVar(@Nullable String passwordVar);

    /**
     * @param deprecated {@code true} if keystore is deprecated
     */
    EnvironmentKeystoreConfigBuilder setDeprecated(boolean deprecated);
}
