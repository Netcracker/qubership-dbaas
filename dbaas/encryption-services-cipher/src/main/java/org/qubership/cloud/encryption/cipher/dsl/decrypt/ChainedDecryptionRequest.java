package org.qubership.cloud.encryption.cipher.dsl.decrypt;

import org.qubership.cloud.encryption.cipher.DecryptResult;
import org.qubership.cloud.encryption.cipher.dsl.ChainedCryptoRequest;

import javax.annotation.Nonnull;

public interface ChainedDecryptionRequest extends ChainedCryptoRequest<ChainedDecryptionRequest> {
    /**
     * Decrypt and return result with parameters specified before <b>Note:</b> encrypted string represent in base64
     * encoding
     * 
     * @param encryptedText not null text that should be decrypt
     * @return decrypted not null result object from that can be read string or byte array
     */
    @Nonnull
    DecryptResult decrypt(@Nonnull String encryptedText);

    /**
     * Decrypt and return result with parameters specified before. Byte array already contain raw data without base64
     * wrapping
     * 
     * @param encryptedBytes not null raw byte array that should be decrypted
     * @return decrypted not null result object from that can be read string or byte array
     */
    @Nonnull
    DecryptResult decrypt(@Nonnull byte[] encryptedBytes);
}
