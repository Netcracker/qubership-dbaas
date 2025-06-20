package org.qubership.cloud.encryption.config.keystore;

import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public interface KeystoreSubsystemConfig {
    /**
     * List keystore configurations that was configured
     * 
     * @return not null list with all keystore config or if keystore conf absent return empty list
     */
    @Nonnull
    List<KeystoreConfig> getKeyStores();

    /**
     * It parameter can't be null if configure at least one keystore
     * 
     * @return correspond keystore configuration or null if keystore not configure
     */
    @Nullable
    KeystoreConfig getDefaultKeyStore();
}
