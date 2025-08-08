package org.qubership.cloud.encryption.cipher.dsl.decrypt;

import org.qubership.cloud.encryption.cipher.CryptoService;
import org.qubership.cloud.encryption.cipher.DecryptResult;
import org.qubership.cloud.encryption.cipher.build.CryptoRequestBuilder;
import org.qubership.cloud.encryption.cipher.build.DecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.build.IDecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.dsl.AbstractChainedCryptoRequestBuilder;

import javax.annotation.Nonnull;

public class ChainedDecryptionRequestBuilder extends AbstractChainedCryptoRequestBuilder<ChainedDecryptionRequest>
        implements ChainedDecryptionRequest {
    @Nonnull
    private final CryptoService cryptoService;
    private final IDecryptionRequestBuilder builder;

    public ChainedDecryptionRequestBuilder(@Nonnull CryptoService cryptoService) {
        this.cryptoService = cryptoService;
        this.builder = DecryptionRequestBuilder.createBuilder();
    }

    @Nonnull
    @Override
    protected ChainedDecryptionRequestBuilder self() {
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
    public DecryptResult decrypt(@Nonnull String encryptedText) {
        return cryptoService.decrypt(builder.setBase64EncryptedText(encryptedText).build());
    }

    @Nonnull
    @Override
    public DecryptResult decrypt(@Nonnull byte[] encryptedBytes) {
        return cryptoService.decrypt(builder.setEncryptedText(encryptedBytes).build());
    }
}
