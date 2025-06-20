package org.qubership.cloud.encryption.config.keystore.type;

import javax.annotation.Nonnull;
import java.util.List;

public interface KeystoreConfig {
    /**
     * Unique identity for key store describe by it configuration
     * 
     * @return not null id
     */
    @Nonnull
    String getKeystoreIdentifier();

    /**
     * Get information about keystore actuality
     * 
     * @return {@code true} if keystore is deprecated and should not be used, {@code false} otherwise
     */
    boolean isDeprecated();

    /**
     * Get specified key configurations
     * 
     * @return list of {code KeyConfig}
     */
    List<KeyConfig> getKeys();
}

