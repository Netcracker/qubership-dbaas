package org.qubership.cloud.encryption.config.keystore.type;

import java.util.List;

public interface MutableEnvironmentKeystoreConfig extends EnvironmentKeystoreConfig {
    /**
     * Set keystore actuality
     * 
     * @param deprecated set {@code true} if keystore is deprecated and should not be used, {@code false} otherwise
     */
    void setDeprecated(boolean deprecated);

    /**
     * Set encryption keys.
     * 
     * @param keys keys
     */
    void setKeys(List<KeyConfig> keys);

    /**
     * Sets the value of the prefix property.
     * 
     * @param value allowed object is {@link String }
     */
    void setPrefix(String value);

    /**
     * Sets the value of the encrypted property.
     * 
     * @param value value
     */
    void setEncrypted(boolean value);

    /**
     * Sets the value of the passwordVar property.
     * 
     * @param value allowed object is {@link String }
     */
    void setPasswordVar(String value);
}
