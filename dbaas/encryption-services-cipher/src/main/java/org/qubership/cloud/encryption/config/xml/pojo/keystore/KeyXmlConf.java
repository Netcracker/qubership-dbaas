package org.qubership.cloud.encryption.config.xml.pojo.keystore;

import org.qubership.cloud.encryption.config.keystore.type.MutableKeyConfig;

import javax.annotation.Nonnull;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * <p>
 * Java class for keyType complex type.
 *
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 *
 * <pre>
 * &lt;complexType name="keyType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;element name="password" type="{http://www.w3.org/2001/XMLSchema}token" minOccurs="0"/>
 *       &lt;/sequence>
 *       &lt;attribute name="alias" use="required" type="{http://www.w3.org/2001/XMLSchema}ID" />
 *       &lt;attribute name="deprecated" type="{http://www.w3.org/2001/XMLSchema}boolean" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 *
 *
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "keyType", propOrder = { "password" })
public class KeyXmlConf implements MutableKeyConfig {

    @XmlElement(required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String password;
    @XmlAttribute(name = "alias", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlID
    @XmlSchemaType(name = "ID")
    protected String alias;
    @XmlAttribute(name = "deprecated")
    protected Boolean deprecated;

    /**
     * Gets the value of the password property.
     *
     * @return possible object is {@link String }
     *
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the value of the password property.
     *
     * @param value allowed object is {@link String }
     *
     */
    public void setPassword(@Nonnull String value) {
        this.password = value;
    }

    /**
     * Gets the value of the alias property.
     *
     * @return possible object is {@link String }
     *
     */
    public String getAlias() {
        return alias;
    }

    /**
     * Sets the value of the alias property.
     *
     * @param value allowed object is {@link String }
     *
     */
    public void setAlias(@Nonnull String value) {
        this.alias = value;
    }

    /**
     * Gets the value of the deprecated property.
     *
     * @return possible object is {@link Boolean }
     *
     */
    public Boolean isDeprecated() {
        return deprecated != null && deprecated;
    }

    /**
     * Sets the value of the deprecated property.
     *
     * @param value allowed object is {@link Boolean }
     *
     */
    public void setDeprecated(boolean value) {
        if (this.deprecated != null || value) {
            this.deprecated = value;
        }

    }

}

