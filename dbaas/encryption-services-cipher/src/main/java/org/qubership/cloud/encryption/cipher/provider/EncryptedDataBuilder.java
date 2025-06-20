package org.qubership.cloud.encryption.cipher.provider;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.key.AliasedKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EncryptedDataBuilder implements IEncryptedDataBuilder, EncryptedData {
    @Nonnull
    @Override
    public EncryptedData build() {
        return this;
    }

    private String algorithm;

    @Nonnull
    @Override
    public String getUsedAlgorithm() {
        return Preconditions.checkNotNull(algorithm, "Algorithm not defined");
    }

    @Nonnull
    @Override
    public IEncryptedDataBuilder setUsedAlgorithm(@Nonnull String algorithm) {
        this.algorithm = algorithm;
        return this;
    }


    private AliasedKey usedKey;

    @Nonnull
    @Override
    public AliasedKey getUsedKey() {
        return Preconditions.checkNotNull(usedKey, "AliasedKey not defined");
    }

    @Nonnull
    @Override
    public IEncryptedDataBuilder setUsedKey(@Nonnull AliasedKey key) {
        this.usedKey = key;
        return this;
    }

    private Optional<byte[]> salt = Optional.absent();

    @Nonnull
    @Override
    public Optional<byte[]> getIV() {
        return salt;
    }

    @Nonnull
    @Override
    public IEncryptedDataBuilder setInitializedVector(@Nullable byte[] salt) {
        this.salt = Optional.fromNullable(salt);
        return this;
    }

    private byte[] encryptedText;

    @Nonnull
    @Override
    public byte[] getEncryptedData() {
        return encryptedText.clone();
    }

    @Nonnull
    @Override
    public IEncryptedDataBuilder setEncryptedText(@Nonnull byte[] encryptedText) {
        this.encryptedText = Preconditions.checkNotNull(encryptedText, "EncryptedText byte array can't be null");
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("algorithm", algorithm).add("usedKey", usedKey).add("salt", salt)
                .add("encryptedText", encryptedText).toString();
    }
}
