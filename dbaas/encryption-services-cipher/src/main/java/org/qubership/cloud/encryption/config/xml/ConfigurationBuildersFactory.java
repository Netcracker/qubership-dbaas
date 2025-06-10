package org.qubership.cloud.encryption.config.xml;

import org.qubership.cloud.encryption.config.EncryptionConfigBuilder;
import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.type.EnvironmentKeystoreConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfigBuilder;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfigBuilder;
import org.qubership.cloud.encryption.config.xml.build.*;

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

