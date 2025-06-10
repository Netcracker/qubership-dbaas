package org.qubership.cloud.encryption.config.keystore.type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.KeyStore;

public interface LocalKeystoreConfig extends KeystoreConfig {
    /**
     * Absolute path where locate to keystore file
     * 
     * @see java.security.KeyStore
     * @return not null path
     */
    @Nonnull
    String getLocation();

    /**
     * Type Keystore. For example JSK
     * 
     * @return not null type
     * @see KeyStore#getType()
     */
    @Nonnull
    String getKeystoreType();

    /**
     * Decrypted password to unlock key store
     * 
     * @return decrypted password or null if password not necessary for access to keystore
     */
    @Nullable
    String getPassword();
}
