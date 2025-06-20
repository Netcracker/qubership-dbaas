package org.qubership.cloud.encryption.config.xml.pojo.keystore;

import com.google.common.base.MoreObjects;
import org.qubership.cloud.encryption.config.keystore.type.MutableLocalKeystoreConfig;

import javax.annotation.Nonnull;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Keystores ensure the secure storage and management of private keys and secret key and trusted certificate authorities
 * (CAs). Keystore locate on server where locate qubership application
 * 
 * <p>
 * Java class for local-keystoreType complex type.
 * 
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="local-keystoreType">
 *   &lt;complexContent>
 *     &lt;extension base="{urn:nc:encryption:keystore:1.0}keystoreType">
 *       &lt;sequence>
 *         &lt;element name="location" type="{http://www.w3.org/2001/XMLSchema}token"/>
 *         &lt;element name="keystore-type" type="{http://www.w3.org/2001/XMLSchema}token"/>
 *         &lt;element name="password" type="{http://www.w3.org/2001/XMLSchema}token"/>
 *       &lt;/sequence>
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "local-keystoreType", propOrder = { "location", "keystoreType", "password" })
public class LocalKeystoreXmlConf extends AbstractKeystoreXmlConf implements MutableLocalKeystoreConfig {

    @XmlElement(required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String location;
    @XmlElement(name = "keystore-type", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String keystoreType;
    @XmlElement(required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    protected String password;


    @Nonnull
    public String getLocation() {
        return location;
    }

    @Nonnull
    @Override
    public String getKeystoreType() {
        return keystoreType;
    }

    /**
     * Sets the value of the location property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setLocation(String value) {
        this.location = value;
    }

    /**
     * Sets the value of the keystoreType property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setKeystoreType(String value) {
        this.keystoreType = value;
    }

    @Nonnull
    public String getPassword() {
        return password;
    }

    /**
     * Sets the value of the password property.
     * 
     * @param value allowed object is {@link String }
     * 
     */
    public void setPassword(String value) {
        this.password = value;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", name).add("location", location)
                .add("keystoreType", keystoreType).add("password", password).toString();
    }
}
