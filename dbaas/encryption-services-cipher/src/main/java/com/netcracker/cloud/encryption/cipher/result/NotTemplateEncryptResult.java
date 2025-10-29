package com.netcracker.cloud.encryption.cipher.result;


import com.netcracker.cloud.encryption.cipher.provider.EncryptedData;

import javax.annotation.Nonnull;

/**
 * Base implementation that doesn't have own implementation template
 */
public class NotTemplateEncryptResult extends AbstractEncryptResult {
    public NotTemplateEncryptResult(@Nonnull EncryptedData encryptedData) {
        super(encryptedData);
    }

    @Nonnull
    @Override
    public String getResultAsEncryptionServiceTemplate() {
        throw new UnsupportedOperationException("Encrypted template not available by encrypted service provider");
    }
}
