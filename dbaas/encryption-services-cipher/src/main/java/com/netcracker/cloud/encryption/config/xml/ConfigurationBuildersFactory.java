package com.netcracker.cloud.encryption.config.xml;

import com.netcracker.cloud.encryption.config.EncryptionConfigBuilder;
import com.netcracker.cloud.encryption.config.crypto.CryptoSubsystemConfigBuilder;
import com.netcracker.cloud.encryption.config.keystore.KeystoreSubsystemConfigBuilder;
import com.netcracker.cloud.encryption.config.keystore.type.EnvironmentKeystoreConfigBuilder;
import com.netcracker.cloud.encryption.config.keystore.type.KeyConfigBuilder;
import com.netcracker.cloud.encryption.config.keystore.type.LocalKeystoreConfigBuilder;
import com.netcracker.cloud.encryption.config.xml.build.*;

import javax.annotation.Nonnull;

@SuppressWarnings("rawtypes")
public class ConfigurationBuildersFactory {
    public EncryptionConfigBuilder getConfigurationBuilder() {
        return new XmlEncryptionConfigurationBuilder();
    }

    public CryptoSubsystemConfigBuilder getCryptoSubsystemConfigBuilder() {
        return new XmlCryptoSubsystemConfigBuilder();
    }

    public KeystoreSubsystemConfigBuilder getKeystoreConfigBuilder() {
        return new XmlKeystoreSubsystemConfigBuilder();
    }

    public LocalKeystoreConfigBuilder getLocalKeystoreConfigBuilder(@Nonnull String identity) {
        return new XmlLocalKeystoreConfigBuilder(identity);
    }

    public EnvironmentKeystoreConfigBuilder getEnvironmentKeystoreConfigBuilder(@Nonnull String identity) {
        return new XmlEnvironmentKeystoreConfigBuilder(identity);
    }

    public KeyConfigBuilder getKeyConfigBuilder(@Nonnull String alias) {
        return new XmlKeyConfigBuilder(alias);
    }
}

