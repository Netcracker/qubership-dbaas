
package org.qubership.cloud.encryption.config.xml.pojo.keystore;

import com.google.common.collect.Lists;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.bind.annotation.*;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.Collections;
import java.util.List;


/**
 * <p>
 * Java class for keystoreType complex type.
 * 
 * <p>
 * The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="keystoreType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;attribute name="name" use="required" type="{http://www.w3.org/2001/XMLSchema}ID" />
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "keystoreType", propOrder = { "keys" })
@XmlSeeAlso({ RemoteKeystoreXmlConf.class, LocalKeystoreXmlConf.class })
public abstract class AbstractKeystoreXmlConf implements KeystoreConfig {
    @XmlElementWrapper(name = "keys")
    @XmlElement(name = "key", type = KeyXmlConf.class)
    @Nullable
    private List<KeyConfig> keys;

    @XmlAttribute(name = "name", required = true)
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlID
    @XmlSchemaType(name = "ID")
    protected String name;

    @XmlAttribute(name = "deprecated")
    protected Boolean deprecated;

    public void setKeystoreIdentifier(@Nonnull String name) {
        this.name = name;
    }

    @Nonnull
    @Override
    public String getKeystoreIdentifier() {
        return name;
    }

    public boolean isDeprecated() {
        return deprecated != null && deprecated;
    }

    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    @Nullable
    public List<KeyConfig> getKeys() {
        if (keys == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(keys);
    }

    public void setKeys(@Nullable List<KeyConfig> keys) {
        this.keys = Lists.newArrayList(keys);
    }

}

