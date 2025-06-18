package org.qubership.cloud.encryption.cipher.build;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.cipher.DecryptionRequest;
import org.qubership.cloud.encryption.cipher.EncryptionMetaInfo;
import org.apache.commons.codec.binary.Base64;

import javax.annotation.Nonnull;
import java.util.Arrays;

public final class DecryptionRequestBuilder extends AbstractCryptoRequestBuilder<IDecryptionRequestBuilder>
        implements IDecryptionRequestBuilder, DecryptionRequest {
    private DecryptionRequestBuilder() {}

    @Nonnull
    public static IDecryptionRequestBuilder createBuilder() {
        return new DecryptionRequestBuilder();
    }

    @Nonnull
    public static IDecryptionRequestBuilder createBuilder(EncryptionMetaInfo metaInfo) {
        DecryptionRequestBuilder builder = new DecryptionRequestBuilder();

        builder.setEncryptedText(metaInfo.getEncryptedData());
        builder.setAlgorithm(metaInfo.getAlgorithm());

        if (metaInfo.getKey() != null) {
            builder.setKey(metaInfo.getKey().getKey());
        }

        if (metaInfo.getIV().isPresent()) {
            builder.setIV(metaInfo.getIV().get());
        }

        return builder;
    }

    @Nonnull
    @Override
    protected IDecryptionRequestBuilder self() {
        return this;
    }

    private byte[] encryptedText;

    @Nonnull
    @Override
    public byte[] getEncryptedText() {
        return Preconditions.checkNotNull(encryptedText, "Encrypted text not initialized yet!");
    }


    @Nonnull
    @Override
    public IDecryptionRequestBuilder setBase64EncryptedText(@Nonnull String encryptedText) {
        Preconditions.checkNotNull(encryptedText, "Encrypted text can't be null");
        this.encryptedText = Base64.decodeBase64(encryptedText);
        return self();
    }

    @Nonnull
    @Override
    public IDecryptionRequestBuilder setEncryptedText(@Nonnull byte[] encryptedBytes) {
        Preconditions.checkNotNull(encryptedBytes, "Encrypted byte array can't be null");
        this.encryptedText = Arrays.copyOf(encryptedBytes, encryptedBytes.length);
        return self();
    }

    @Nonnull
    @Override
    public DecryptionRequest build() {
        Preconditions.checkNotNull(encryptedText, "EncryptedText it required parameters and they not specify");
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("algorithm", getAlgorithm()).add("key", getKey())
                .add("keyAlias", getKeyAlias()).add("encryptedText", Arrays.toString(encryptedText)).toString();
    }
}

