package org.qubership.cloud.encryption.config.keystore.type;


import javax.annotation.Nonnull;

public interface KeyConfigBuilder {

    /**
     * @return create new instance
     */
    KeyConfig build();

    /**
     * @param password password of key. May be empty if key is not protected with a password.
     */
    KeyConfigBuilder setPassword(@Nonnull String password);

    /**
     * @param deprecated {@code true} if the key is deprecated
     */
    KeyConfigBuilder setDeprecated(boolean deprecated);

    /**
     * Copy all parameters from given KeyConfig to current one.
     * 
     * @param keyConfig non-null KeyConfig instance
     */
    KeyConfigBuilder copyParameters(@Nonnull KeyConfig keyConfig);

}

