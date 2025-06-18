package org.qubership.cloud.encryption.cipher.dsl.encrypt;

import org.qubership.cloud.encryption.cipher.EncryptResult;
import org.qubership.cloud.encryption.cipher.dsl.ChainedCryptoRequest;

import javax.annotation.Nonnull;

public interface ChainedEncryptionRequest extends ChainedCryptoRequest<ChainedEncryptionRequest> {
    /**
     * Encrypt data with parameters that was set before <b>Note:</b> target plain text will be decode with use UTF-8
     * encoding, for especial encoding should be use {@link ChainedEncryptionRequest#encrypt(byte[])} method
     * 
     * @param plainText not null data that should be encrypted. Like data can be set empty string.
     * @return encrypted text
     * @throws NullPointerException if specified data is null
     */
    @Nonnull
    EncryptResult encrypt(@Nonnull String plainText);

    /**
     * Encrypt byte array with parameters that was set before
     * 
     * @param plainText not null byte array that should be encrypted
     * @return encrypted text
     * @throws java.lang.NullPointerException if specified plaint ext is null
     */
    @Nonnull
    EncryptResult encrypt(@Nonnull byte[] plainText);
}
