package org.qubership.cloud.encryption.config.xml.pojo.keystore;

import com.google.common.base.MoreObjects;
import org.qubership.cloud.encryption.config.keystore.type.MutableEnvironmentKeystoreConfig;

import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;


/**
 * Keys are passed through environment variables.
 * 
 * <p>
 * Java class for environment-keystoreType complex type.
 * 
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="environment-keystoreType">
 *   &lt;complexContent>
 *     &lt;extension base="{urn:nc:encryption:keystore:1.0}keystoreType">
 *       &lt;sequence>
 *         &lt;element name="prefix" type="{http://www.w3.org/2001/XMLSchema}token" minOccurs="0"/>
 *         &lt;element name="encrypted" type="{http://www.w3.org/2001/XMLSchema}boolean"/>
 *         &lt;element name="passwordVar" type="{http://www.w3.org/2001/XMLSchema}token" minOccurs="0"/>
 *         &lt;element name="defaultKeyVar" type="{http://www.w3.org/2001/XMLSchema}token"/>
 *       &lt;/sequence>
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "environment-keystoreType", propOrder = { "prefix", "encrypted", "passwordVar", "defaultKeyVar" })
public class EnvironmentKeystoreXmlConf extends AbstractKeystoreXmlConf implements MutableEnvironmentKeystoreConfig {

    @XmlElement(defaultValue = "ENV_KEY_")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String prefix;

    @XmlElement(defaultValue = "false")
    protected boolean encrypted;

    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String passwordVar;

    @XmlElement(required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String defaultKeyVar;

    /**
     * Gets the value of the prefix property.
     * 
     * @return possible object is {@link String }
     * 
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Sets the value of the prefix property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setPrefix(String value) {
        this.prefix = value;
    }

    /**
     * Gets the value of the encrypted property.
     * 
     */
    public boolean isEncrypted() {
        return encrypted;
    }

    /**
     * Sets the value of the encrypted property.
     * 
     */
    public void setEncrypted(boolean value) {
        this.encrypted = value;
    }

    /**
     * Gets the value of the passwordVar property.
     * 
     * @return possible object is {@link String }
     * 
     */
    public String getPasswordVar() {
        return passwordVar;
    }

    /**
     * Sets the value of the passwordVar property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setPasswordVar(String value) {
        this.passwordVar = value;
    }

    /**
     * Gets the value of the defaultKeyVar property.
     * 
     * @return possible object is {@link String }
     * 
     */
    public String getDefaultKeyVar() {
        return defaultKeyVar;
    }

    /**
     * Sets the value of the defaultKeyVar property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setDefaultKeyVar(String value) {
        this.defaultKeyVar = value;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", name).add("prefix", prefix).add("encrypted", encrypted)
                .add("passwordVar", passwordVar).add("defaultKeyVar", defaultKeyVar).toString();
    }
}
