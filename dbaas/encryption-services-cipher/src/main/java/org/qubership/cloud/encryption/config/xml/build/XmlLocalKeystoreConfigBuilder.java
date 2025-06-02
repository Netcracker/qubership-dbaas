package org.qubership.cloud.encryption.config.xml.build;

import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.type.MutableLocalKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.LocalKeystoreXmlConf;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.ObjectFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class XmlLocalKeystoreConfigBuilder implements LocalKeystoreConfigBuilder {
    MutableLocalKeystoreConfig mutableConfig;

    public XmlLocalKeystoreConfigBuilder(@Nonnull String identity) {
        LocalKeystoreXmlConf keystoreXmlConf = new ObjectFactory().createLocalKeystoreType();
        keystoreXmlConf.setKeystoreIdentifier(identity);
        this.mutableConfig = keystoreXmlConf;
    }

    @Nonnull
    @Override
    public LocalKeystoreConfig build() {
        return mutableConfig;
    }

    @Nonnull
    @Override
    public LocalKeystoreConfigBuilder copyParameters(LocalKeystoreConfig config) {
        setLocation(config.getLocation());
        setKeystoreType(config.getKeystoreType());
        setPassword(config.getPassword());
        setKeys(config.getKeys());
        setDeprecated(config.isDeprecated());
        return this;
    }

    @Override
    public LocalKeystoreConfigBuilder setLocation(@Nonnull String keyStoreLocation) {
        mutableConfig.setLocation(keyStoreLocation);
        return this;
    }

    @Override
    public LocalKeystoreConfigBuilder setKeystoreType(@Nonnull String type) {
        mutableConfig.setKeystoreType(type);
        return this;
    }

    @Override
    public LocalKeystoreConfigBuilder setPassword(@Nullable String password) {
        mutableConfig.setPassword(password);
        return this;
    }

    @Override
    public LocalKeystoreConfigBuilder setDeprecated(boolean deprecated) {
        mutableConfig.setDeprecated(deprecated);
        return this;
    }

    @Override
    public LocalKeystoreConfigBuilder setKeys(List<KeyConfig> keys) {
        mutableConfig.setKeys(keys);
        return this;
    }
}

