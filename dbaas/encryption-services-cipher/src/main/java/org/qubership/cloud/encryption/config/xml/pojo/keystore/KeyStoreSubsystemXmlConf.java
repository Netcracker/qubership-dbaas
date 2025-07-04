package org.qubership.cloud.encryption.config.xml.pojo.keystore;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.qubership.cloud.encryption.config.keystore.MutableKeystoreSubsystemConfig;
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
 *         &lt;element name="keystores" type="{urn:nc:encryption:keystore:1.0}listKeyStoreType"/>
 *         &lt;element name="default-keystore" type="{http://www.w3.org/2001/XMLSchema}IDREF" minOccurs="0"/>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "subsystemType", propOrder = { "keystores", "defaultKeystore" })
public class KeyStoreSubsystemXmlConf implements MutableKeystoreSubsystemConfig {
    @XmlElementWrapper(name = "keystores")
    @XmlElements({ @XmlElement(name = "environment-keystore", type = EnvironmentKeystoreXmlConf.class),
            @XmlElement(name = "local-keystore", type = LocalKeystoreXmlConf.class),
            @XmlElement(name = "remote-keystore", type = RemoteKeystoreXmlConf.class) })
    @Nullable
    private List<KeystoreConfig> keystores;

    @XmlElement(name = "default-keystore")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    @XmlSchemaType(name = "token")
    private String defaultKeystore;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("keystores", keystores).add("defaultKeystore", defaultKeystore)
                .toString();
    }

    @Nonnull
    @Override
    public List<KeystoreConfig> getKeyStores() {
        if (keystores == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(keystores);
    }

    @Nullable
    @Override
    public KeystoreConfig getDefaultKeyStore() {
        if (defaultKeystore == null) {
            return Iterables.getFirst(getKeyStores(), null);
        }

        return getKeystoreConfigById(defaultKeystore);
    }

    @Nullable
    private KeystoreConfig getKeystoreConfigById(String keystoreId) {
        for (KeystoreConfig keystoreConfig : getKeyStores()) {
            if (keystoreConfig.getKeystoreIdentifier().equals(keystoreId)) {
                return keystoreConfig;
            }
        }

        return null;
    }

    @Override
    public void setKeyStores(@Nonnull List<? extends KeystoreConfig> keyStores) {
        keystores = Lists.newArrayList(keyStores);
    }

    @Override
    public void setDefaultKeyStore(KeystoreConfig keyStore) {
        // todo check it?
        defaultKeystore = keyStore.getKeystoreIdentifier();
    }
}
