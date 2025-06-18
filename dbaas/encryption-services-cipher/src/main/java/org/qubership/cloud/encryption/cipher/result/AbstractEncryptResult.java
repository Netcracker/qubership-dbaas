package org.qubership.cloud.encryption.cipher.result;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.cipher.EncryptResult;
import org.qubership.cloud.encryption.cipher.provider.EncryptedData;
import org.apache.commons.codec.binary.Base64;

import javax.annotation.Nonnull;

public abstract class AbstractEncryptResult implements EncryptResult {
    @Nonnull
    private final EncryptedData cryptoResult;

    public AbstractEncryptResult(@Nonnull EncryptedData cryptoResult) {
        Preconditions.checkNotNull(cryptoResult, "EncryptedData can't be null");
        this.cryptoResult = cryptoResult;
    }

    @Nonnull
    @Override
    public byte[] getResultAsByteArray() {
        return cryptoResult.getEncryptedData();
    }

    @Nonnull
    @Override
    public EncryptedData getEncryptedData() {
        return cryptoResult;
    }

    @Nonnull
    @Override
    public String getResultAsBase64String() {
        return Base64.encodeBase64String(getResultAsByteArray());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("cryptoResult", cryptoResult).toString();
    }
}
