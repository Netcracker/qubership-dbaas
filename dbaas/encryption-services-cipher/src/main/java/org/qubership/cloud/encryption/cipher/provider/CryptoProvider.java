package org.qubership.cloud.encryption.cipher.provider;

import org.qubership.cloud.encryption.cipher.*;
import org.qubership.cloud.encryption.cipher.exception.EncryptException;
import org.qubership.cloud.encryption.cipher.exception.IllegalCryptoParametersException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;

/**
 * Provider that process encryption {@link org.qubership.cloud.encryption.cipher.EncryptionRequest} and decryption
 * {@link org.qubership.cloud.encryption.cipher.DecryptResult} Different providers can have different encrypted
 * template format, and for support many version template need create different templates
 */
@ThreadSafe
public interface CryptoProvider {
    /**
     * Encrypt data with parameters like algorithm, key, and so on that define inside {@link EncryptionRequest}
     * 
     * @param request not null request contains all required parameters
     * @return encrypted text
     * @throws NullPointerException if specified request is null
     * @throws IllegalCryptoParametersException specified parameters be initialize correctly for encryption specified
     *         text for example because specified not exist algorithm or key that can't be apply for specified algorithm
     * @throws EncryptException some exception occurs during encrypt specified data
     */
    @Nonnull
    EncryptResult encrypt(@Nonnull EncryptionRequest request);

    /**
     * Decrypt data with parameters like algorithm, key, and so on that define inside {@link DecryptionRequest}
     * 
     * @param request not null request contains all required parameters
     * @return decrypted result
     * @throws NullPointerException if specified request is null
     * @throws IllegalCryptoParametersException parameter can't be initialize correctly for decrypt specified text for
     *         example when specified not exists algorithm
     * @throws org.qubership.cloud.encryption.cipher.exception.DecryptException some exception occurs during decrypt
     *         specified data
     */
    @Nonnull
    DecryptResult decrypt(@Nonnull DecryptionRequest request);

    /**
     * Check support or not it provider format in that was encrypted text
     * 
     * @param encryptedByTemplateText not null encrypted text for decrypt
     * @return {@code true} if encrypted format belongs current provider and they can extract information from it
     *         encrypted data, otherwise return {@code false}
     */
    boolean isKnowEncryptedFormat(@Nonnull String encryptedByTemplateText);

    /**
     * Decrypt encrypted by template text, in template contains all required information for decryption
     * 
     * @param encryptedByTemplateText not null encrypted by template text
     * @return decrypt result
     */
    DecryptResult decrypt(@Nonnull String encryptedByTemplateText);

    /**
     * Define parameters that can be processes by current provider that was specify on {@link EncryptionRequest} or
     * {@link DecryptionRequest} If in the next release component was added new parameters, but provider was not update,
     * if will be use old provider that not consider parameters it lead to difficult to find bugs, but if list
     * parameters that can process provider define in advance service layer can not delegate encryption request that
     * contain parameters that can not process it provider
     * 
     * @return not null list supports parameters
     */
    @Nonnull
    Set<CryptoParameter> getSupportsCryptoParameters();

    /**
     * Get information about encrypted text if it was obtained using template
     * {@link EncryptResult#getResultAsEncryptionServiceTemplate()}
     * 
     * @param encryptedByTemplateText encrypted text that was built according with template
     * @return {@link EncryptionMetaInfo} with corresponding data or {@code null} if given text doesn't match any known
     *         template
     */
    @Nullable
    EncryptionMetaInfo getEncryptedMetaInfo(@Nonnull String encryptedByTemplateText);

}

