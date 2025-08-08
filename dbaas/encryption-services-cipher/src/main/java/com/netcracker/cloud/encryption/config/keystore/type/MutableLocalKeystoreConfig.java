package org.qubership.cloud.encryption.config.keystore.type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public interface MutableLocalKeystoreConfig extends LocalKeystoreConfig {
    /**
     * Set Absolute path where locate to keystore file
     * 
     * @see java.security.KeyStore
     */
    void setLocation(@Nonnull String keyStoreLocation);

    /**
     * Type Keystore. For example JSK
     */
    void setKeystoreType(@Nonnull String type);

    /**
     * @param password password for unlock keystore or null if keystore can be open without password
     */
    void setPassword(@Nullable String password);

    /**
     * Set keystore actuality
     * 
     * @param deprecated set {@code true} if keystore is deprecated and should not be used, {@code false} otherwise
     */
    void setDeprecated(boolean deprecated);

    void setKeys(List<KeyConfig> key);
}

