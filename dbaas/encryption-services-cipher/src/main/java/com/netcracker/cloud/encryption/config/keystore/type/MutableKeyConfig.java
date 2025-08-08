package org.qubership.cloud.encryption.config.keystore.type;

import javax.annotation.Nonnull;

public interface MutableKeyConfig extends KeyConfig {

    /**
     * Set key name
     *
     * @param alias not-null key alias
     */
    void setAlias(@Nonnull String alias);

    /**
     * Set key password.
     *
     * @param password not-null key password
     */
    void setPassword(@Nonnull String password);

    /**
     * Set key actuality
     *
     * @param deprecated set {@code true} if key is deprecated and should not be used, {@code false} otherwise
     */
    void setDeprecated(boolean deprecated);

}

