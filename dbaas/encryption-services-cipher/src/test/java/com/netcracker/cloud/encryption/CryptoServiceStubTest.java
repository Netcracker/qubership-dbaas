package com.netcracker.cloud.encryption;

import org.junit.jupiter.api.Test;
import org.qubership.cloud.encryption.cipher.CryptoService;
import org.hamcrest.Matchers;

import static org.hamcrest.MatcherAssert.assertThat;

class CryptoServiceStubTest {

    @Test
    void testEncryptText() {
        String plainText = "secret";

        CryptoService cryptoService = new CryptoServiceStub();

        String cryptedText = cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();

        assertThat(cryptedText, Matchers.not(Matchers.equalTo(plainText)));
    }

    @Test
    void testEncryptAndDecrypt() {
        String plainText = "secret";

        CryptoService cryptoService = new CryptoServiceStub();

        String encryptText = cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();

        String decryptText = cryptoService.decryptDSLRequest().decrypt(encryptText).getResultAsString();

        assertThat(decryptText, Matchers.equalTo(plainText));
    }
}

