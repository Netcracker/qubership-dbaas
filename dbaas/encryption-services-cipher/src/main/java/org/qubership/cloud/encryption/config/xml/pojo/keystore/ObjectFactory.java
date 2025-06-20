
package org.qubership.cloud.encryption.config.xml.pojo.keystore;

import javax.xml.bind.annotation.XmlRegistry;

/**
 * This object contains factory methods for each Java content interface and Java element interface generated in the
 * nc.encryption.keystore._1 package.
 * <p>
 * An ObjectFactory allows you to programmatically construct new instances of the Java representation for XML content.
 * The Java representation of XML content can consist of schema derived interfaces and classes representing the binding
 * of schema type definitions, element declarations and model groups. Factory methods for each of these are provided in
 * this class.
 * 
 */
@XmlRegistry
public class ObjectFactory implements KeystoreConfigFactory {

    /**
     * Create an instance of {@link KeyStoreSubsystemXmlConf }
     * 
     */
    public KeyStoreSubsystemXmlConf createSubsystemType() {
        return new KeyStoreSubsystemXmlConf();
    }

    /**
     * Create an instance of {@link RemoteKeystoreXmlConf }
     * 
     */
    public RemoteKeystoreXmlConf createRemoteKeystoreType() {
        return new RemoteKeystoreXmlConf();
    }

    /**
     * Create an instance of {@link EnvironmentKeystoreXmlConf }
     * 
     */
    public EnvironmentKeystoreXmlConf createEnvironmentKeystoreType() {
        return new EnvironmentKeystoreXmlConf();
    }

    /**
     * Create an instance of {@link LocalKeystoreXmlConf }
     * 
     */
    public LocalKeystoreXmlConf createLocalKeystoreType() {
        return new LocalKeystoreXmlConf();
    }

    public KeyXmlConf createKeyType() {
        return new KeyXmlConf();
    }

}

