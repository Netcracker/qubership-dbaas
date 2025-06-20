package org.qubership.cloud.encryption.cipher.build;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import java.security.Key;

@SuppressWarnings("rawtypes")
public interface CryptoRequestBuilder<T extends CryptoRequestBuilder> {
    /**
     * Define algorithm that should be apply for encryption / decryption
     * 
     * @param algorithmName not null name algorithm name
     * @return builder
     * @throws java.lang.NullPointerException if specified algorithmName is null
     * @see javax.crypto.Cipher
     */
    @Nonnull
    T setAlgorithm(@Nonnull String algorithmName);

    /**
     * Define JCA provider that should be use for process request
     * 
     * @param providerName not null name prover name
     * @return builder
     * @throws java.lang.NullPointerException if specified algorithmName is null
     * @see javax.crypto.Cipher
     */
    @Nonnull
    T setProvider(@Nonnull String providerName);

    /**
     * Define key that should be apply for encryption / decryption. Opposite method
     * {@link CryptoRequestBuilder#setKeyAlias(String)} that more preferable if key stores in global KeyStore
     * 
     * @param key not null secret key
     * @return builder
     * @throws NullPointerException if specified key is null
     * @see SecretKey
     * @see org.qubership.cloud.encryption.key.KeyStore
     */
    @Nonnull
    T setKey(@Nonnull Key key);

    /**
     * Define key alias that should be find in
     * {@link org.qubership.cloud.encryption.key.KeyStore#getKeyByAlias(String)} and use for encryption / decryption
     * it method opposite for use key explicitly {@link CryptoRequestBuilder#setKey(Key)}
     * 
     * @param aliasKey not null unique name for key
     * @return builder
     * @see org.qubership.cloud.encryption.key.KeyStore#getKeyByAlias(String)
     */
    @Nonnull
    T setKeyAlias(@Nonnull String aliasKey);

    /**
     * Define InitializedVector for apply encryption/decryption
     * 
     * @param vector initialized vector that should be use during encryption/decryption
     * @return builder
     */
    @Nonnull
    T setIV(@Nullable byte[] vector);
}

