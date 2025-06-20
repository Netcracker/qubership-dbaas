package org.qubership.cloud.encryption.cipher.build;

import org.qubership.cloud.encryption.cipher.EncryptionRequest;

import javax.annotation.Nonnull;

public interface IEncryptionRequestBuilder extends CryptoRequestBuilder<IEncryptionRequestBuilder> {
    /**
     * <b>Note:</b> target plain text will be decode with use UTF-8 encoding, for especial encoding should be use
     * {@link IEncryptionRequestBuilder#setPlainText(byte[])} method
     * 
     * @param plainText not null data that should be encrypted. Like data can be set empty string.
     * @return builder
     * @throws NullPointerException if specified data is null
     */
    @Nonnull
    IEncryptionRequestBuilder setPlainText(@Nonnull String plainText);

    /**
     * @param plainText not null byte array that should be encrypted
     * @return builder
     * @throws java.lang.NullPointerException if specified plaint ext is null
     */
    @Nonnull
    IEncryptionRequestBuilder setPlainText(@Nonnull byte[] plainText);


    /**
     * @return create build request
     */
    @Nonnull
    EncryptionRequest build();
}
