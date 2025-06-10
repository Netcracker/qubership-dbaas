package org.qubership.cloud.encryption.config.keystore;

import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;

import javax.annotation.Nonnull;
import java.util.List;

public interface KeystoreSubsystemConfigBuilder<T extends KeystoreSubsystemConfigBuilder<T>> {
    /**
     * @return new configuration instance
     */
    @Nonnull
    KeystoreSubsystemConfig build();

    /**
     * @param config configuration from that need copy parameters
     * @return builder
     */
    @Nonnull
    T copyParameters(@Nonnull KeystoreSubsystemConfig config);

    /**
     * Register keystore configurations, each configuration should have unique name
     * 
     * @param keyStores not null list with keystore configurations
     * @return builder
     */
    @Nonnull
    T setKeyStores(@Nonnull List<? extends KeystoreConfig> keyStores);

    /**
     * KeyStoreConfig that that be use like default keystore
     * 
     * @return builder
     */
    @Nonnull
    T setDefaultKeyStore(@Nonnull KeystoreConfig keyStore);
}
