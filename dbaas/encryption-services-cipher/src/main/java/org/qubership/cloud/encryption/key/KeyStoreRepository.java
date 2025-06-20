package org.qubership.cloud.encryption.key;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Common interface for access to all key stores
 */
public interface KeyStoreRepository {
    /**
     * Find keystore by unique name in repository
     * 
     * @param identity not null unique identity for keystore
     * @return correspond keystore or null if they not found
     */
    @Nullable
    KeyStore getKeyStoreByIdentity(@Nonnull String identity);

    /**
     * @return keystore that mark in configuration like default or null if keystore not configure
     */
    @Nullable
    KeyStore getDefaultKeystore();

    /**
     * @return names of all keystores that this repository contains
     */
    @Nonnull
    Set<String> getKeyStoresIdentities();
}

