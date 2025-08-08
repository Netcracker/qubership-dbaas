package org.qubership.cloud.encryption.config.xml.build;

import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.type.MutableKeyConfig;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.KeyXmlConf;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.ObjectFactory;

import javax.annotation.Nonnull;

public class XmlKeyConfigBuilder implements KeyConfigBuilder {
    private MutableKeyConfig mutableKeyConfig;

    public XmlKeyConfigBuilder(@Nonnull String alias) {
        KeyXmlConf keyType = new ObjectFactory().createKeyType();
        keyType.setAlias(alias);
        this.mutableKeyConfig = keyType;
    }

    @Override
    public KeyConfig build() {
        return mutableKeyConfig;
    }

    @Override
    public KeyConfigBuilder setPassword(@Nonnull String password) {
        mutableKeyConfig.setPassword(password);
        return this;
    }

    @Override
    public KeyConfigBuilder setDeprecated(boolean deprecated) {
        mutableKeyConfig.setDeprecated(deprecated);
        return this;
    }

    @Override
    public KeyConfigBuilder copyParameters(@Nonnull KeyConfig keyConfig) {
        setPassword(keyConfig.getPassword());
        setDeprecated(keyConfig.isDeprecated());
        return this;
    }
}

