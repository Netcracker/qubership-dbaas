package org.qubership.cloud.encryption.key;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.Key;
import java.util.List;

/**
 * Database with encryption key like symmetric/asymmetric
 */
public interface KeyStore {
    /**
     * @return Unique identity for keystore
     */
    @Nonnull
    String getIdentity();

    /**
     * Find key by alias name
     * 
     * @param aliasName not null unique alias for key
     * @param password password of the key
     * @return correspond key or {@code null} if they absent in KeyStore
     */
    @Nullable
    Key getKeyByAlias(@Nonnull String aliasName, String password);

    /**
     * Find key by alias name. If the key is protected by a password, the password will be obtained from the
     * configuration.
     * 
     * @param aliasName not null unique alias for key
     * @return correspond key or {@code null} if they absent in KeyStore
     */
    @Nullable
    Key getKeyByAlias(@Nonnull String aliasName);

    /**
     * Find key by alias name and type in KeyStore
     * 
     * @param aliasName not null unique alias for key
     * @param keyType not null class for key
     * @param <T> key class
     * @return correspond key or {@code null} if they absent in KeyStore or have different type, for example if we wan
     *         that it was key for symmetric encryption {@link javax.crypto.SecretKey} but in KeyStore by it alias
     *         register key for asymmetric encryption {@link java.security.PrivateKey} result will be {@code null}
     */
    <T extends Key> T getKeyByAlias(@Nonnull String aliasName, @Nonnull Class<T> keyType);

    /**
     * Find key by alias name
     * 
     * @param aliasName not null unique alias for key
     * @return {@link AliasedKey} with additional information about key: alias, deprecated or not, etc. or {@code null}
     *         if the key is absent in the keystore
     */
    @Nullable
    AliasedKey getAliasedKey(@Nonnull String aliasName);

    /**
     * Get information about keystore actuality
     * 
     * @return {@code true} if keystore is deprecated and should not be used, {@code false} otherwise
     */
    boolean isDeprecated();

    /**
     * Get all key aliases contained in this keystore.
     * 
     * @return list of all aliases
     */
    List<String> getAliases();
}

