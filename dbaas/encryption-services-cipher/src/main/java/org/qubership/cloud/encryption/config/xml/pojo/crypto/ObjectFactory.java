package org.qubership.cloud.encryption.config.xml.pojo.crypto;

import javax.xml.bind.annotation.XmlRegistry;

/**
 * This object contains factory methods for each Java content interface and Java element interface generated in the
 * nc.encryption.crypto._1 package.
 * <p>
 * An ObjectFactory allows you to programmatically construct new instances of the Java representation for XML content.
 * The Java representation of XML content can consist of schema derived interfaces and classes representing the binding
 * of schema type definitions, element declarations and model groups. Factory methods for each of these are provided in
 * this class.
 * 
 */
@XmlRegistry
public class ObjectFactory implements CryptoConfigFactory {
    /**
     * Create an instance of {@link CryptoSubsystemXmlConf }
     * 
     */
    public CryptoSubsystemXmlConf createSubsystemType() {
        return new CryptoSubsystemXmlConf();
    }
}
