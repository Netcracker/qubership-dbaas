package org.qubership.cloud.encryption.config.xml.build;

import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfigBuilder;
import org.qubership.cloud.encryption.config.crypto.MutableCryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.xml.pojo.crypto.ObjectFactory;

import javax.annotation.Nonnull;

public class XmlCryptoSubsystemConfigBuilder implements CryptoSubsystemConfigBuilder<XmlCryptoSubsystemConfigBuilder> {
    private XmlCryptoSubsystemConfigBuilder self() {
        return this;
    }

    private final MutableCryptoSubsystemConfig mutableConfig;

    public XmlCryptoSubsystemConfigBuilder() {
        this.mutableConfig = new ObjectFactory().createSubsystemType();
    }

    @Nonnull
    @Override
    public CryptoSubsystemConfig build() {
        return mutableConfig;
    }

    @Nonnull
    @Override
    public XmlCryptoSubsystemConfigBuilder copyParameters(CryptoSubsystemConfig config) {
        if (config.getDefaultAlgorithm().isPresent()) {
            setDefaultAlgorithm(config.getDefaultAlgorithm().get());
        }
        if (config.getDefaultKeyAlias().isPresent()) {
            setDefaultKeyAlias(config.getDefaultKeyAlias().get());
        }
        if (config.getKeyStoreName().isPresent()) {
            setKeyStoreName(config.getKeyStoreName().get());
        }
        return self();
    }

    @Nonnull
    @Override
    public XmlCryptoSubsystemConfigBuilder setDefaultAlgorithm(@Nonnull String algorithm) {
        mutableConfig.setDefaultAlgorithm(algorithm);
        return self();
    }

    @Nonnull
    @Override
    public XmlCryptoSubsystemConfigBuilder setKeyStoreName(@Nonnull String keyStoreName) {
        mutableConfig.setKeyStoreName(keyStoreName);
        return self();
    }

    @Nonnull
    @Override
    public XmlCryptoSubsystemConfigBuilder setDefaultKeyAlias(@Nonnull String defaultKeyAlias) {
        mutableConfig.setDefaultKeyAlias(defaultKeyAlias);
        return self();
    }
}
