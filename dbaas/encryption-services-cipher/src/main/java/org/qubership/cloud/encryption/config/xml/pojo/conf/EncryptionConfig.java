
package org.qubership.cloud.encryption.config.xml.pojo.conf;

import com.google.common.base.MoreObjects;
import org.qubership.cloud.encryption.config.MutableEncryptionConfiguration;
import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.xml.pojo.crypto.CryptoSubsystemXmlConf;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.KeyStoreSubsystemXmlConf;

import javax.annotation.Nonnull;
import javax.xml.bind.annotation.*;


/**
 * <p>
 * Java class for anonymous complex type.
 * 
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType>
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="keystore-subsystem" type="{urn:nc:encryption:keystore:1.0}subsystemType"/>
 *         &lt;element name="encryption-subsystem" type="{urn:nc:encryption:crypto:1.0}subsystemType"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = { "keystoreSubsystem", "encryptionSubsystem" })
@XmlRootElement(name = "security-config")
public class EncryptionConfig implements MutableEncryptionConfiguration {
    @XmlElement(name = "keystore-subsystem", required = true, type = KeyStoreSubsystemXmlConf.class)
    private KeystoreSubsystemConfig keystoreSubsystem;
    @XmlElement(name = "encryption-subsystem", required = true, type = CryptoSubsystemXmlConf.class)
    private CryptoSubsystemConfig encryptionSubsystem;

    @Override
    public void setCryptoSubsystemConfig(@Nonnull CryptoSubsystemConfig cryptoSubsystemConfig) {
        this.encryptionSubsystem = cryptoSubsystemConfig;
    }

    @Override
    public void setKeystoreSubsystemConfig(@Nonnull KeystoreSubsystemConfig keystoreSubsystemConfig) {
        this.keystoreSubsystem = keystoreSubsystemConfig;
    }

    @Nonnull
    @Override
    public KeystoreSubsystemConfig getKeyStoreSubsystemConfig() {
        return keystoreSubsystem;
    }

    @Nonnull
    @Override
    public CryptoSubsystemConfig getCryptoSubsystemConfig() {
        return encryptionSubsystem;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("keystoreSubsystemCfg", keystoreSubsystem)
                .add("encryptionSubsystemCfg", encryptionSubsystem).toString();
    }
}

