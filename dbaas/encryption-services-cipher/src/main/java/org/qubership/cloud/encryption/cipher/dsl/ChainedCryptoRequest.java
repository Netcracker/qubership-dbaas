package org.qubership.cloud.encryption.cipher.dsl;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import javax.crypto.SecretKey;
import java.security.Key;

/**
 * Builder for set common parameters for process encryption or decryption
 */
@SuppressWarnings("rawtypes")
@NotThreadSafe
public interface ChainedCryptoRequest<T extends ChainedCryptoRequest> {
    /**
     * Define algorithm that should be apply for encryption / decryption
     * 
     * @param algorithmName not null name algorithm name
     * @return builder
     * @throws java.lang.NullPointerException if specified algorithmName is null
     * @see javax.crypto.Cipher
     */
    @Nonnull
    T algorithm(@Nonnull String algorithmName);

    /**
     * Define JCA provider that should be use for encryption / decryption
     * 
     * @param providerName not null name provider name
     * @return builder
     * @throws java.lang.NullPointerException if specified providerName is null
     * @see javax.crypto.Cipher
     */
    @Nonnull
    T provider(@Nonnull String providerName);

    /**
     * Define key that should be apply for encryption / decryption. Opposite method
     * {@link ChainedCryptoRequest#keyAlias(String)} that more preferable if key stores in global KeyStore
     * 
     * @param key not null secret key
     * @return builder
     * @throws NullPointerException if specified key is null
     * @see SecretKey
     * @see org.qubership.cloud.encryption.key.KeyStore
     */
    @Nonnull
    T key(@Nonnull Key key);

    /**
     * Define key alias that should be find in
     * {@link org.qubership.cloud.encryption.key.KeyStore#getKeyByAlias(String)} and use for encryption / decryption
     * it method opposite for use key explicitly {@link ChainedCryptoRequest#key(Key)}
     * 
     * @param aliasKey not null unique name for key
     * @return builder
     * @see org.qubership.cloud.encryption.key.KeyStore#getKeyByAlias(String)
     */
    @Nonnull
    T keyAlias(@Nonnull String aliasKey);

    /**
     * Define initialized vector(salt) for encrypt/decrypt
     * 
     * @param vector not null byte array
     * @return builder
     */
    @Nonnull
    T initializedVector(@Nonnull byte[] vector);
}

