package org.qubership.cloud.encryption.cipher.build;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.cipher.EncryptionRequest;

import javax.annotation.Nonnull;
import java.nio.charset.Charset;
import java.util.Arrays;

public final class EncryptionRequestBuilder extends AbstractCryptoRequestBuilder<IEncryptionRequestBuilder>
        implements IEncryptionRequestBuilder, EncryptionRequest {
    private EncryptionRequestBuilder() {}

    @Nonnull
    public static IEncryptionRequestBuilder createBuilder() {
        return new EncryptionRequestBuilder();
    }

    @Nonnull
    @Override
    protected IEncryptionRequestBuilder self() {
        return this;
    }

    private byte[] plainText;

    @Nonnull
    @Override
    public byte[] getPlainText() {
        return Preconditions.checkNotNull(plainText, "PlainText not specified yet!");
    }

    @Nonnull
    @Override
    public IEncryptionRequestBuilder setPlainText(@Nonnull String plainText) {
        Preconditions.checkNotNull(plainText, "Plain text can't be null");
        this.plainText = plainText.getBytes(Charset.forName("UTF-8"));
        return self();
    }

    @Nonnull
    @Override
    public IEncryptionRequestBuilder setPlainText(@Nonnull byte[] plainText) {
        Preconditions.checkNotNull(plainText, "Plain text byte array can't be null");
        this.plainText = Arrays.copyOf(plainText, plainText.length);
        return self();
    }

    @Nonnull
    @Override
    public EncryptionRequest build() {
        Preconditions.checkNotNull(plainText, "PlainText required parameter that not specify");
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("algorithm", getAlgorithm()).add("key", getKey())
                .add("keyAlias", getKeyAlias()).add("plainText", Arrays.toString(plainText)).toString();
    }
}
