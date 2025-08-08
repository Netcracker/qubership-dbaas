package org.qubership.cloud.encryption.cipher.exception;

import org.qubership.cloud.encryption.cipher.CryptoRequest;

import javax.annotation.Nonnull;

@SuppressWarnings("serial")
public class NotFoundSuitableCryptoProvider extends CryptoException {
    @Nonnull
    private final CryptoRequest cryptoRequest;

    public NotFoundSuitableCryptoProvider(@Nonnull CryptoRequest request) {
        super(String.format(
                "Not found CryptoProvider that can process request: {%s} with all explicit specify parameters",
                request));
        this.cryptoRequest = request;
    }

    @Nonnull
    public CryptoRequest getCryptoRequest() {
        return cryptoRequest;
    }
}
