package org.qubership.cloud.encryption.config.xml.pojo.crypto;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import org.qubership.cloud.encryption.config.crypto.MutableCryptoSubsystemConfig;

import javax.annotation.Nonnull;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Default parameters for encrypt/decrypt providers
 * 
 * <p>
 * Java class for subsystemType complex type.
 * 
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="subsystemType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="default-algorithm" type="{http://www.w3.org/2001/XMLSchema}token" minOccurs="0"/>
 *         &lt;element name="keystore" type="{http://www.w3.org/2001/XMLSchema}token" minOccurs="0"/>
 *         &lt;element name="default-key-alias" type="{http://www.w3.org/2001/XMLSchema}token" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "subsystemType", propOrder = { "defaultAlgorithm", "keystore", "defaultKeyAlias" })
public class CryptoSubsystemXmlConf implements MutableCryptoSubsystemConfig {

    @XmlElement(name = "default-algorithm")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    private String defaultAlgorithm;
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    private String keystore;
    @XmlElement(name = "default-key-alias")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    private String defaultKeyAlias;

    /**
     * Gets the value of the defaultAlgorithm property.
     * 
     * @return possible object is {@link String }
     * 
     */
    @Nonnull
    public Optional<String> getDefaultAlgorithm() {
        return Optional.fromNullable(defaultAlgorithm);
    }

    /**
     * Sets the value of the defaultAlgorithm property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setDefaultAlgorithm(@Nonnull String value) {
        this.defaultAlgorithm = value;
    }


    @Nonnull
    @Override
    public Optional<String> getKeyStoreName() {
        return Optional.fromNullable(keystore);
    }


    /**
     * Gets the value of the defaultKeyAlias property.
     * 
     * @return possible object is {@link String }
     * 
     */
    @Nonnull
    public Optional<String> getDefaultKeyAlias() {
        return Optional.fromNullable(defaultKeyAlias);
    }

    /**
     * Sets the value of the defaultKeyAlias property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setDefaultKeyAlias(@Nonnull String value) {
        this.defaultKeyAlias = value;
    }

    @Override
    public void setKeyStoreName(@Nonnull String keyStoreName) {
        this.keystore = keyStoreName;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("defaultAlgorithm", defaultAlgorithm).add("keystore", keystore)
                .add("defaultKeyAlias", defaultKeyAlias).toString();
    }
}
