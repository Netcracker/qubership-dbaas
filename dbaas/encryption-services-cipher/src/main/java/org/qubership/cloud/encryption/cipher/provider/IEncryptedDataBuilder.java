package org.qubership.cloud.encryption.cipher.provider;

import org.qubership.cloud.encryption.key.AliasedKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IEncryptedDataBuilder {
    /**
     * @return new instance with specified parameters
     */
    @Nonnull
    EncryptedData build();

    /**
     * @param algorithm not null algorithm that was use for encrypt
     * @return builder
     */
    @Nonnull
    IEncryptedDataBuilder setUsedAlgorithm(@Nonnull String algorithm);

    /**
     * @param key not null key that was use for encrypt
     * @return builder
     */
    @Nonnull
    IEncryptedDataBuilder setUsedKey(@Nonnull AliasedKey key);

    /**
     * @param salt initialized vector that use like salt for encrypt text
     * @return builder
     */
    @Nonnull
    IEncryptedDataBuilder setInitializedVector(@Nullable byte[] salt);

    /**
     * @param encryptedText encryption result
     * @return builder
     */
    @Nonnull
    IEncryptedDataBuilder setEncryptedText(@Nonnull byte[] encryptedText);
}
