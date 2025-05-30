package org.qubership.cloud.encryption.config.keystore.type;


public interface KeyConfig {

    /**
     * Unique name of the key
     * 
     * @return not null alias
     */
    public String getAlias();

    /**
     * Password of the key.
     * 
     * @return not null key password
     */
    public String getPassword();

    /**
     * Get information about key actuality
     * 
     * @return {@code true} if the key is deprecated and should not be used, {@code false} otherwise
     */
    public Boolean isDeprecated();
}

