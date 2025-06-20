package org.qubership.cloud.encryption.config.xml.pojo.conf;

import org.qubership.cloud.encryption.config.xml.pojo.crypto.CryptoConfigFactory;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.KeystoreConfigFactory;

import javax.xml.bind.annotation.XmlRegistry;


/**
 * This object contains factory methods for each Java content interface and Java element interface generated in the
 * nc.encryption.conf._1 package.
 * <p>
 * An ObjectFactory allows you to programatically construct new instances of the Java representation for XML content.
 * The Java representation of XML content can consist of schema derived interfaces and classes representing the binding
 * of schema type definitions, element declarations and model groups. Factory methods for each of these are provided in
 * this class.
 * 
 */
@XmlRegistry
public class ObjectFactory implements RootConfigFactory {

    /**
     * Create an instance of {@link EncryptionConfig }
     *
     */
    public EncryptionConfig createEncryptionConfig() {
        EncryptionConfig config = new EncryptionConfig();
        config.setCryptoSubsystemConfig(getCryptoConfigFactory().createSubsystemType());
        config.setKeystoreSubsystemConfig(getKeystoreConfigFactory().createSubsystemType());

        return config;
    }

    private KeystoreConfigFactory getKeystoreConfigFactory() {
        return new org.qubership.cloud.encryption.config.xml.pojo.keystore.ObjectFactory();
    }

    private CryptoConfigFactory getCryptoConfigFactory() {
        return new org.qubership.cloud.encryption.config.xml.pojo.crypto.ObjectFactory();
    }
}
