package org.qubership.cloud.encryption.cipher;

import org.qubership.cloud.encryption.cipher.provider.EncryptedData;

import javax.annotation.Nonnull;

/**
 * Result container for apply encryption by some algorithm, key, and so on for plain text
 */
public interface EncryptResult {
    /**
     * @return encryption result as is
     */
    @Nonnull
    byte[] getResultAsByteArray();

    /**
     * Encode to base 64 encrypted byte array
     * 
     * @return not null base64(encryptedByteArray)
     */
    @Nonnull
    String getResultAsBase64String();

    /**
     * @return encrypted data container that contain information about use algorithm, salt, and so on
     */
    @Nonnull
    EncryptedData getEncryptedData();

    /**
     * Apply base64 encoding encrypted data and also inject by template algorithm, key alias, and another information
     * that helps decrypt data when was changed default encryption parameters. <b>Note:</b> Text encrypted by it way can
     * be decrypted only by component that encrypt it(encryption-service).
     * 
     * @return not null encrypted text with injected parameters helps during migration
     * @exception org.qubership.cloud.encryption.cipher.exception.NotAvailableInjectionEncryptParamsException if was
     *            specified parameters for encrypt that can't be inject
     */
    @Nonnull
    String getResultAsEncryptionServiceTemplate();
}
