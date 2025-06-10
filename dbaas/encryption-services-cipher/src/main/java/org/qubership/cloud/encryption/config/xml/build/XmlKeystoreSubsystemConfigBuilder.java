package org.qubership.cloud.encryption.config.xml.build;

import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.MutableKeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.ObjectFactory;

import javax.annotation.Nonnull;
import java.util.List;

public class XmlKeystoreSubsystemConfigBuilder
        implements KeystoreSubsystemConfigBuilder<XmlKeystoreSubsystemConfigBuilder> {
    @Nonnull
    private final MutableKeystoreSubsystemConfig subsystemConfig;

    public XmlKeystoreSubsystemConfigBuilder() {
        this.subsystemConfig = new ObjectFactory().createSubsystemType();
    }

    @Nonnull
    @Override
    public KeystoreSubsystemConfig build() {
        return subsystemConfig;
    }

    @Nonnull
    @Override
    public XmlKeystoreSubsystemConfigBuilder copyParameters(@Nonnull KeystoreSubsystemConfig config) {
        setKeyStores(config.getKeyStores());
        if (config.getDefaultKeyStore() != null) {
            setDefaultKeyStore(config.getDefaultKeyStore());
        }
        return this;
    }

    @Nonnull
    @Override
    public XmlKeystoreSubsystemConfigBuilder setDefaultKeyStore(@Nonnull KeystoreConfig keyStore) {
        subsystemConfig.setDefaultKeyStore(keyStore);
        return this;
    }

    @Nonnull
    @Override
    public XmlKeystoreSubsystemConfigBuilder setKeyStores(@Nonnull List<? extends KeystoreConfig> keyStores) {
        subsystemConfig.setKeyStores(keyStores);
        return this;
    }
}
