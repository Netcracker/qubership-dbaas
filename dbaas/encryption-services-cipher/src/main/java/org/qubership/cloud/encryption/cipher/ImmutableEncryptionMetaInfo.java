package org.qubership.cloud.encryption.cipher;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.key.AliasedKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

public class ImmutableEncryptionMetaInfo implements EncryptionMetaInfo {

    private final byte[] encryptedData;
    private final String algorithm;
    private final AliasedKey key;
    private Optional<byte[]> iv = Optional.absent();

    public ImmutableEncryptionMetaInfo(@Nonnull byte[] encryptedData, @Nonnull String algorithm,
            @Nullable AliasedKey key, @Nullable byte[] iv) {
        this.encryptedData = Preconditions.checkNotNull(encryptedData, "Encrypted data byte array can't be null");
        this.algorithm = Preconditions.checkNotNull(algorithm, "Algorithm can't be null");
        this.key = key;

        if (iv != null) {
            this.iv = Optional.of(iv.clone());
        }
    }

    @Nonnull
    @Override
    public byte[] getEncryptedData() {
        return Arrays.copyOf(encryptedData, encryptedData.length);
    }

    @Nonnull
    @Override
    public String getAlgorithm() {
        return algorithm;
    }

    @Nullable
    @Override
    public AliasedKey getKey() {
        return key;
    }

    @Nullable
    @Override
    public Optional<byte[]> getIV() {
        return iv;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("algorithm", getAlgorithm()).add("key", getKey())
                .add("encryptedText", Arrays.toString(encryptedData)).toString();
    }
}

