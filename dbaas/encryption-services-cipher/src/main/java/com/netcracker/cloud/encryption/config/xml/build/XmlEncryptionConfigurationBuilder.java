package com.netcracker.cloud.encryption.config.xml.build;

import com.netcracker.cloud.encryption.config.EncryptionConfigBuilder;
import com.netcracker.cloud.encryption.config.EncryptionConfiguration;
import com.netcracker.cloud.encryption.config.MutableEncryptionConfiguration;
import com.netcracker.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import com.netcracker.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import com.netcracker.cloud.encryption.config.xml.pojo.conf.ObjectFactory;

import javax.annotation.Nonnull;

public class XmlEncryptionConfigurationBuilder implements EncryptionConfigBuilder<XmlEncryptionConfigurationBuilder> {
    private final MutableEncryptionConfiguration configuration;

    public XmlEncryptionConfigurationBuilder() {
        this.configuration = new ObjectFactory().createEncryptionConfig();
    }

    private XmlEncryptionConfigurationBuilder self() {
        return this;
    }

    @Nonnull
    @Override
    public EncryptionConfiguration build() {
        return configuration;
    }

    @Override
    public XmlEncryptionConfigurationBuilder copyParameters(@Nonnull EncryptionConfiguration config) {
        setCryptoSubsystemConfig(config.getCryptoSubsystemConfig());
        return self();
    }

    @Override
    public XmlEncryptionConfigurationBuilder setCryptoSubsystemConfig(
            @Nonnull CryptoSubsystemConfig cryptoSubsystemConfig) {
        configuration.setCryptoSubsystemConfig(cryptoSubsystemConfig);
        return self();
    }

    @Override
    public XmlEncryptionConfigurationBuilder setKeystoreSubsystemConfig(
            @Nonnull KeystoreSubsystemConfig keystoreSubsystemConfig) {
        configuration.setKeystoreSubsystemConfig(keystoreSubsystemConfig);
        return self();
    }
}
