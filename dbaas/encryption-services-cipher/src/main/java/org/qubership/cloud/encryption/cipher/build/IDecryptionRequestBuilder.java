package org.qubership.cloud.encryption.cipher.build;

import org.qubership.cloud.encryption.cipher.DecryptionRequest;

import javax.annotation.Nonnull;

public interface IDecryptionRequestBuilder extends CryptoRequestBuilder<IDecryptionRequestBuilder> {
    /**
     * <b>Note:</b> encrypted string represent in base64 encoding
     * 
     * @param encryptedText not null text that should be decrypt
     * @return builder
     */
    @Nonnull
    IDecryptionRequestBuilder setBase64EncryptedText(@Nonnull String encryptedText);

    /**
     * Byte array already contain raw data without base64 wrapping
     * 
     * @param encryptedBytes not null raw byte array that should be decrypted
     * @return builder
     */
    @Nonnull
    IDecryptionRequestBuilder setEncryptedText(@Nonnull byte[] encryptedBytes);

    /**
     * @return request with specified parameters
     */
    @Nonnull
    DecryptionRequest build();
}

