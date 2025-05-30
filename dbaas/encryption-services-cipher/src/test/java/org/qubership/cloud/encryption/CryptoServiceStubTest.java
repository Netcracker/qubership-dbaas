package org.qubership.cloud.encryption;

import org.qubership.cloud.encryption.cipher.CryptoService;
import org.hamcrest.Matchers;
import org.junit.Test;

import static org.junit.Assert.assertThat;

public class CryptoServiceStubTest {

    @Test
    public void testEncryptText() throws Exception {
        String plainText = "secret";

        CryptoService cryptoService = new CryptoServiceStub();

        String cryptedText = cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();

        assertThat(cryptedText, Matchers.not(Matchers.equalTo(plainText)));
    }

    @Test
    public void testEncryptAndDecrypt() throws Exception {
        String plainText = "secret";

        CryptoService cryptoService = new CryptoServiceStub();

        String encryptText = cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();

        String decryptText = cryptoService.decryptDSLRequest().decrypt(encryptText).getResultAsString();

        assertThat(decryptText, Matchers.equalTo(plainText));
    }
}

