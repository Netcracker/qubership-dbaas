package org.qubership.cloud.encryption.config.crypto;

import javax.annotation.Nonnull;

public interface CryptoSubsystemConfigBuilder<T extends CryptoSubsystemConfigBuilder<T>> {
    /**
     * @return new instance with specified parameters
     */
    @Nonnull
    CryptoSubsystemConfig build();

    /**
     * @param config not null configuration that need copy all parameters
     * @return builder
     */
    @Nonnull
    T copyParameters(CryptoSubsystemConfig config);

    /**
     * @param algorithm not null JCA algorithm name
     * @return builder
     */
    @Nonnull
    T setDefaultAlgorithm(@Nonnull String algorithm);


    /**
     * @param keyStoreName not null reference to keystore name
     * @return builder
     */
    @Nonnull
    T setKeyStoreName(@Nonnull String keyStoreName);

    /**
     * @param defaultKeyAlias not null key alias from keystore
     * @return builder
     */
    @Nonnull
    T setDefaultKeyAlias(@Nonnull String defaultKeyAlias);

}
