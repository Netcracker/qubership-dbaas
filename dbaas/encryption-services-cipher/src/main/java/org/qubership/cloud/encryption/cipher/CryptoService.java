package org.qubership.cloud.encryption.cipher;

import org.qubership.cloud.encryption.cipher.dsl.decrypt.ChainedDecryptionRequest;
import org.qubership.cloud.encryption.cipher.dsl.encrypt.ChainedEncryptionRequest;
import org.qubership.cloud.encryption.cipher.exception.EncryptException;
import org.qubership.cloud.encryption.cipher.exception.IllegalCryptoParametersException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Common service for access to cryptographic symmetric and asymmetric encryption
 */
@ThreadSafe
public interface CryptoService {
    /**
     * <p>
     * Encrypt data with default parameters like algorithm, key and so on
     * </p>
     * <p>
     * <b>Note:</b> from data will be get byte array in UTF-8 encoding, for use different type encode need use
     * {@link CryptoService#encrypt(byte[])}<br/>
     * <b>Note 2:</b> encrypted text by it method can be decrypted only via {@link CryptoService} for decrypt text in
     * any place please use {@link CryptoService#encrypt(EncryptionRequest)} and
     * {@link EncryptResult#getResultAsByteArray()}
     * </p>
     * 
     * @param data not null data that should be encrypted. Like data can be set empty string.
     * @return encrypted text
     * @throws NullPointerException if specified data is null
     * @throws IllegalCryptoParametersException default parameter can't be initialize correctly for encryption specified
     *         text
     * @throws EncryptException some exception occurs during encrypt specified data
     */
    @Nonnull
    String encrypt(@Nonnull String data);

    /**
     * <p>
     * Encrypt data with default parameters like algorithm, key and so on
     * </p>
     * <p>
     * <b>Note:</b> encrypted text by it method can be decrypted only via {@link CryptoService} for decrypt text in any
     * place please use {@link CryptoService#encrypt(EncryptionRequest)} and
     * {@link EncryptResult#getResultAsByteArray()}
     * </p>
     * 
     * @param data not null byte array that should be encrypted
     * @return encrypted text
     * @throws NullPointerException if specified data is null
     * @throws IllegalCryptoParametersException default parameter can't be initialize correctly for encryption specified
     *         text
     * @throws EncryptException some exception occurs during encrypt specified data
     */
    @Nonnull
    String encrypt(@Nonnull byte[] data);

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
     * Decrypt data with read required parameters from string data <b>Note:</b> by it method available decrypt only data
     * encrypted via {@link CryptoService#encrypt(String)} or {@link CryptoService#encrypt(byte[])} or
     * {@link EncryptResult#getResultAsEncryptionServiceTemplate()}
     * 
     * @param data data in template that define {@link CryptoService}, it template contain
     * @return not null decrypted result that can be convert to text
     * @throws NullPointerException if specified data is null
     * @throws IllegalCryptoParametersException parameter can't be initialize correctly for decryption specified text
     *         because for example not available key that was use for encrypt
     * @throws org.qubership.cloud.encryption.cipher.exception.DecryptException some exception occurs during decrypt
     *         specified data
     */
    @Nonnull
    DecryptResult decrypt(@Nonnull String data);

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
     * DSL that allow encrypttext by easy way. Example usage:
     * 
     * <pre>
     * {
     *     &#64;code
     *     final String jcaAlgorithm = "AES/ECB/PKCS5Padding";
     *     final SecretKey symetricKey = KeyGenerator.getInstance("AES").generateKey(); // can be replace to loockup
     *     final String plainText = "secret";
     *
     *     // CryptoService cryptoService = ...;
     *
     *     final String encryptedText = cryptoService.encryptDSLRequest().algorithm(jcaAlgorithm).key(symetricKey)
     *             .encrypt(plainText).getResultAsBase64String();
     *
     *     final String decryptedText = cryptoService.decryptDSLRequest().algorithm(jcaAlgorithm).key(symetricKey)
     *             .decrypt(encryptedText).getResultAsString();
     *
     *     assert plainText.equals(decryptedText);
     * }
     * </pre>
     * 
     * @return dsl builder
     */
    @Nonnull
    ChainedEncryptionRequest encryptDSLRequest();

    /**
     * DSL that allow decrypt text by easy way. Example usage:
     * 
     * <pre>
     * {
     *     &#64;code
     *     final String jcaAlgorithm = "AES/ECB/PKCS5Padding";
     *     final SecretKey symetricKey = KeyGenerator.getInstance("AES").generateKey(); // can be replace to loockup
     *     final String plainText = "secret";
     *
     *     // CryptoService cryptoService = ...;
     *
     *     final String encryptedText = cryptoService.encryptDSLRequest().algorithm(jcaAlgorithm).key(symetricKey)
     *             .encrypt(plainText).getResultAsBase64String();
     *
     *     final String decryptedText = cryptoService.decryptDSLRequest().algorithm(jcaAlgorithm).key(symetricKey)
     *             .decrypt(encryptedText).getResultAsString();
     *
     *     assert plainText.equals(decryptedText);
     * }
     * </pre>
     * 
     * @return dsl builder
     */
    @Nonnull
    ChainedDecryptionRequest decryptDSLRequest();

    /**
     * Get information about encrypted text if it was obtained using template
     * {@link EncryptResult#getResultAsEncryptionServiceTemplate()}
     * 
     * @param encryptedData encrypted text that was built according with template
     * @return {@link EncryptionMetaInfo} with corresponding data or {@code null} if given text doesn't match any known
     *         template
     */
    @Nullable
    EncryptionMetaInfo getEncryptedMetaInfo(@Nonnull String encryptedData);
}

