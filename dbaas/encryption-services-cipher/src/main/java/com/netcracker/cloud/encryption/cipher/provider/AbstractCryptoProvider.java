package com.netcracker.cloud.encryption.cipher.provider;

import com.netcracker.cloud.encryption.cipher.CryptoParameter;
import com.netcracker.cloud.encryption.cipher.DecryptResult;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;

public abstract class AbstractCryptoProvider implements CryptoProvider {
    @Override
    public boolean isKnowEncryptedFormat(@Nonnull String encryptedByTemplateText) {
        return false;
    }

    @Override
    public DecryptResult decrypt(@Nonnull String encryptedByTemplateText) {
        throw new UnsupportedOperationException(
                "CryptoProvider not support decrypt from template: " + encryptedByTemplateText);
    }

    @Nonnull
    @Override
    public Set<CryptoParameter> getSupportsCryptoParameters() {
        return Collections.emptySet();
    }
}
