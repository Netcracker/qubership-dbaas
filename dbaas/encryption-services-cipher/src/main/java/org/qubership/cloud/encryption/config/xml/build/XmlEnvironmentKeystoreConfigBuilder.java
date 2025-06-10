package org.qubership.cloud.encryption.config.xml.build;

import org.qubership.cloud.encryption.config.keystore.type.EnvironmentKeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.EnvironmentKeystoreConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.MutableEnvironmentKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.EnvironmentKeystoreXmlConf;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.ObjectFactory;

import java.util.List;

public class XmlEnvironmentKeystoreConfigBuilder implements EnvironmentKeystoreConfigBuilder {
    private MutableEnvironmentKeystoreConfig mutableConfig;

    public XmlEnvironmentKeystoreConfigBuilder(String identity) {
        EnvironmentKeystoreXmlConf keystoreXmlConf = new ObjectFactory().createEnvironmentKeystoreType();
        keystoreXmlConf.setKeystoreIdentifier(identity);
        this.mutableConfig = keystoreXmlConf;
    }

    @Override
    public EnvironmentKeystoreConfig build() {
        return mutableConfig;
    }

    @Override
    public EnvironmentKeystoreConfigBuilder copyParameters(EnvironmentKeystoreConfig config) {
        setKeys(config.getKeys());
        setDeprecated(config.isDeprecated());

        setPrefix(config.getPrefix());
        setEncrypted(config.isEncrypted());
        setPasswordVar(config.getPasswordVar());

        return this;
    }

    @Override
    public EnvironmentKeystoreConfigBuilder setKeys(List<KeyConfig> keys) {
        mutableConfig.setKeys(keys);
        return this;
    }

    @Override
    public EnvironmentKeystoreConfigBuilder setDeprecated(boolean deprecated) {
        mutableConfig.setDeprecated(deprecated);
        return this;
    }

    @Override
    public EnvironmentKeystoreConfigBuilder setPrefix(String prefix) {
        mutableConfig.setPrefix(prefix);
        return this;
    }

    @Override
    public EnvironmentKeystoreConfigBuilder setEncrypted(boolean encrypted) {
        mutableConfig.setEncrypted(encrypted);
        return this;
    }

    @Override
    public EnvironmentKeystoreConfigBuilder setPasswordVar(String password) {
        mutableConfig.setPasswordVar(password);
        return this;
    }
}
