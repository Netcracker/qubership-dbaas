package org.qubership.cloud.encryption.cipher.build;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import org.qubership.cloud.encryption.cipher.CryptoRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.security.Key;

public abstract class AbstractCryptoRequestBuilder<T extends CryptoRequestBuilder<T>>
        implements CryptoRequestBuilder<T>, CryptoRequest {
    @Nonnull
    protected abstract T self();

    @Nonnull
    private Optional<String> algorithm = Optional.absent();

    @Nonnull
    @Override
    public T setAlgorithm(@Nonnull String algorithmName) {
        this.algorithm = Optional.of(algorithmName);
        return self();
    }

    @Nonnull
    @Override
    public Optional<String> getAlgorithm() {
        return algorithm;
    }

    @Nonnull
    private Optional<String> provider = Optional.absent();

    @Nonnull
    @Override
    public T setProvider(@Nonnull String providerName) {
        this.provider = Optional.of(providerName);
        return self();
    }

    @Nonnull
    @Override
    public Optional<String> getProvider() {
        return provider;
    }

    @Nonnull
    private Optional<Key> key = Optional.absent();

    @Nonnull
    @Override
    public T setKey(@Nonnull Key key) {
        this.key = Optional.of(key);
        return self();
    }

    @Nonnull
    @Override
    public Optional<Key> getKey() {
        return key;
    }

    @Nonnull
    private Optional<String> keyAlias = Optional.absent();

    @Nonnull
    @Override
    public T setKeyAlias(@Nonnull String aliasKey) {
        this.keyAlias = Optional.of(aliasKey);
        return self();
    }

    @Nonnull
    @Override
    public Optional<String> getKeyAlias() {
        return keyAlias;
    }

    private Optional<byte[]> initializedVector = Optional.absent();

    @Nonnull
    @Override
    public Optional<byte[]> getIV() {
        return initializedVector;
    }

    @Nonnull
    @Override
    public T setIV(@Nullable byte[] vector) {
        if (vector != null) {
            this.initializedVector = Optional.of(vector.clone());
        }
        return self();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("algorithm", algorithm).add("key", key).add("keyAlias", keyAlias)
                .add("iv", initializedVector).toString();
    }
}

