package org.qubership.cloud.encryption.cipher.dsl;

import com.google.common.base.MoreObjects;
import org.qubership.cloud.encryption.cipher.build.CryptoRequestBuilder;

import javax.annotation.Nonnull;
import java.security.Key;

public abstract class AbstractChainedCryptoRequestBuilder<T extends ChainedCryptoRequest<T>>
        implements ChainedCryptoRequest<T> {
    @Nonnull
    protected abstract T self();

    @SuppressWarnings("rawtypes")
    @Nonnull
    protected abstract CryptoRequestBuilder getBuilder();

    @Nonnull
    @Override
    public T algorithm(@Nonnull String algorithmName) {
        getBuilder().setAlgorithm(algorithmName);
        return self();
    }

    @Nonnull
    @Override
    public T provider(@Nonnull String providerName) {
        getBuilder().setProvider(providerName);
        return self();
    }

    @Nonnull
    @Override
    public T key(@Nonnull Key key) {
        getBuilder().setKey(key);
        return self();
    }

    @Nonnull
    @Override
    public T keyAlias(@Nonnull String aliasKey) {
        getBuilder().setKeyAlias(aliasKey);
        return self();
    }

    @Nonnull
    @Override
    public T initializedVector(@Nonnull byte[] vector) {
        getBuilder().setIV(vector);
        return self();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("builder", getBuilder()).toString();
    }
}

