package com.netcracker.cloud.encryption.cipher.dsl.encrypt;

import com.netcracker.cloud.encryption.cipher.CryptoService;
import com.netcracker.cloud.encryption.cipher.EncryptResult;
import com.netcracker.cloud.encryption.cipher.build.CryptoRequestBuilder;
import com.netcracker.cloud.encryption.cipher.build.EncryptionRequestBuilder;
import com.netcracker.cloud.encryption.cipher.build.IEncryptionRequestBuilder;
import com.netcracker.cloud.encryption.cipher.dsl.AbstractChainedCryptoRequestBuilder;

import javax.annotation.Nonnull;

public class ChainedEncryptionRequestBuilder extends AbstractChainedCryptoRequestBuilder<ChainedEncryptionRequest>
        implements ChainedEncryptionRequest {
    @Nonnull
    private final CryptoService cryptoService;
    @Nonnull
    private final IEncryptionRequestBuilder builder;

    public ChainedEncryptionRequestBuilder(@Nonnull CryptoService cryptoService) {
        this.cryptoService = cryptoService;
        this.builder = EncryptionRequestBuilder.createBuilder();
    }

    @Nonnull
    @Override
    protected ChainedEncryptionRequestBuilder self() {
        return this;
    }

    @SuppressWarnings("rawtypes")
    @Nonnull
    @Override
    protected CryptoRequestBuilder getBuilder() {
        return builder;
    }

    @Nonnull
    @Override
    public EncryptResult encrypt(@Nonnull String plainText) {
        return cryptoService.encrypt(builder.setPlainText(plainText).build());
    }

    @Nonnull
    @Override
    public EncryptResult encrypt(@Nonnull byte[] plainText) {
        return cryptoService.encrypt(builder.setPlainText(plainText).build());
    }
}
