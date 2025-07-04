package org.qubership.cloud.encryption.config.keystore;

import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;

import javax.annotation.Nonnull;
import java.util.List;

public interface MutableKeystoreSubsystemConfig extends KeystoreSubsystemConfig {
    /**
     * Register keystore configurations, each configuration should have unique name
     * 
     * @param keyStores not null list with keystore configurations
     */
    void setKeyStores(@Nonnull List<? extends KeystoreConfig> keyStores);

    /**
     * KeyStoreConfig that that be use like default keystore
     */
    void setDefaultKeyStore(KeystoreConfig keyStore);
}
