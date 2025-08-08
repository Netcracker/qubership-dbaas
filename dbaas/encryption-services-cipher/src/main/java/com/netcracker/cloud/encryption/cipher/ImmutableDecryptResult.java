package org.qubership.cloud.encryption.cipher;

import com.google.common.base.Preconditions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.Charset;
import java.util.Arrays;

public class ImmutableDecryptResult implements DecryptResult {
    @Nonnull
    private final byte[] result;
    @Nonnull
    private final Charset charset;

    public ImmutableDecryptResult(@Nonnull final byte[] result, @Nullable final Charset charset) {
        this.result = Preconditions.checkNotNull(result, "Result byte array can't be null");
        this.charset = charset != null ? charset : Charset.defaultCharset();
    }

    @Nonnull
    @Override
    public byte[] getResultAsByteArray() {
        return Arrays.copyOf(result, result.length);
    }

    @Nonnull
    @Override
    public String getResultAsString() {
        return new String(result, charset);
    }

    @Override
    public String toString() {
        return "DecryptResult[" + Arrays.toString(result) + "]";
    }
}
