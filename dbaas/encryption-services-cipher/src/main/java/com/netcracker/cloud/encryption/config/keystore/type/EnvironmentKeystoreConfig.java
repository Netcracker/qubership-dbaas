package org.qubership.cloud.encryption.config.keystore.type;

/**
 * The class includes configuration of
 * <ns2:security-config xmlns="urn:nc:encryption:keystore:1.0" xmlns:ns2="urn:nc:encryption:conf:1.0" xmlns:ns3=
 * "urn:nc:encryption:crypto:1.0"> <ns2:keystore-subsystem> <keystores>
 * <environment-keystore name="auto-generated-keystore_-3961352350117453936"> <prefix>KS_</prefix>
 * <encrypted>true</encrypted> <passwordVar>SYM_KEY</passwordVar> </environment-keystore> </keystores>
 * <default-keystore>auto-generated-keystore_-3961352350117453936</default-keystore> </ns2:keystore-subsystem>
 * <ns2:encryption-subsystem> <ns3:default-algorithm>AES/CBC/PKCS5Padding</ns3:default-algorithm>
 * <ns3:keystore>auto-generated-keystore_-3961352350117453936</ns3:keystore>
 * <ns3:default-key-alias>DEFAULT_KEY</ns3:default-key-alias> </ns2:encryption-subsystem> </ns2:security-config>
 * 
 */
public interface EnvironmentKeystoreConfig extends KeystoreConfig {
    /**
     * Gets the prefix of environment variables with encryption keys.
     * 
     * @return value of the prefix property possible object is {@link String }
     */
    String getPrefix();

    /**
     * The property is specified the environment variables with keys are encrypted.
     * 
     * @return value of the encrypted property
     */
    boolean isEncrypted();

    /**
     * Gets the name of the environment variable with password.
     * 
     * @return value of the passwordVar property possible object is {@link String }
     * 
     */
    String getPasswordVar();

    /**
     * Gets the name of environment variable with default key.
     * 
     * @return value of the defaultKeyVar property possible object is {@link String }
     * 
     */
    String getDefaultKeyVar();
}
