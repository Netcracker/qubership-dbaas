package org.qubership.cloud.encryption.key;

import com.google.common.base.Optional;

import javax.annotation.Nonnull;
import java.security.Key;

public interface AliasedKey {
    /**
     * Alias for key in keystore
     * 
     * @return optional
     */
    @Nonnull
    Optional<String> getAlias();

    /**
     * Key that will be use for crypto function
     * 
     * @return not null key
     */
    @Nonnull
    Key getKey();

    /**
     * Is key set deprecated in the configuration. If parent keystore is deprecated, key that was obtained from this
     * keystore, is deprecated as well.
     * 
     * @return {@code true} either the key is deprecated implicitly or if the key was obtained from the deprecated
     *         keystore
     */
    boolean isDeprecated();
}

