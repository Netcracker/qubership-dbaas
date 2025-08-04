package org.qubership.cloud.encryption.cipher;

import org.junit.jupiter.api.Test;
import org.qubership.cloud.encryption.cipher.build.DecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.build.EncryptionRequestBuilder;
import org.hamcrest.Matchers;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;

// todo maybe parametrized test?
class CryptoParameterTest {
    @Test
    void testRequestWithDefaultParametersEmpty() throws Exception {
        EncryptionRequest request = EncryptionRequestBuilder.createBuilder().setPlainText("plaintext").build();

        Set<CryptoParameter> result = CryptoParameter.whatParameterDefined(request);

        assertThat(
                "When parameter not specify, by contract whatParameterDefined method should return parameters that was set explicitly",
                result, Matchers.emptyIterable());
    }

    @Test
    void testRequestContainExplicitAlgorithm() throws Exception {
        EncryptionRequest request =
                EncryptionRequestBuilder.createBuilder().setAlgorithm("DES").setPlainText("secret").build();

        Set<CryptoParameter> result = CryptoParameter.whatParameterDefined(request);

        assertThat("Explicitly was specify only algorithm", result,
                allOf(Matchers.<CryptoParameter>iterableWithSize(1), Matchers.hasItem(CryptoParameter.ALGORITHM)));
    }

    @Test
    void testRequestContainExplicitKeyAlias() throws Exception {
        EncryptionRequest request =
                EncryptionRequestBuilder.createBuilder().setKeyAlias("secretKey").setPlainText("secret").build();

        Set<CryptoParameter> result = CryptoParameter.whatParameterDefined(request);

        assertThat("Explicitly was specify only algorithm", result,
                allOf(Matchers.<CryptoParameter>iterableWithSize(1), Matchers.hasItem(CryptoParameter.KEY_ALIS)));
    }

    @Test
    void testRequestContainExplicitKey() throws Exception {
        SecretKey secretKey = KeyGenerator.getInstance("DES").generateKey();

        EncryptionRequest request =
                EncryptionRequestBuilder.createBuilder().setKey(secretKey).setPlainText("secret").build();

        Set<CryptoParameter> result = CryptoParameter.whatParameterDefined(request);

        assertThat("Explicitly was specify only key", result,
                allOf(Matchers.<CryptoParameter>iterableWithSize(1), Matchers.hasItem(CryptoParameter.KEY)));
    }

    @Test
    void testRequestContainExplicitAlogorithmAndKeyAlias() throws Exception {
        EncryptionRequest request = EncryptionRequestBuilder.createBuilder().setAlgorithm("AES").setKeyAlias("MyKey")
                .setPlainText("secret").build();

        Set<CryptoParameter> result = CryptoParameter.whatParameterDefined(request);

        assertThat("Explicitly was specify only key alias and alorithm", result,
                allOf(Matchers.<CryptoParameter>iterableWithSize(2),
                        Matchers.hasItems(CryptoParameter.KEY_ALIS, CryptoParameter.ALGORITHM)));
    }

    @Test
    void testDecryptRequestDefineAlogirhmAndKeyAlias() throws Exception {
        DecryptionRequest request = DecryptionRequestBuilder.createBuilder().setAlgorithm("AES").setKeyAlias("MyKey")
                .setEncryptedText(new byte[] { 0x1, 0x3, 0x5 }).build();

        Set<CryptoParameter> result = CryptoParameter.whatParameterDefined(request);

        assertThat("Explicitly was specify only key alias and alorithm", result,
                allOf(Matchers.<CryptoParameter>iterableWithSize(2),
                        Matchers.hasItems(CryptoParameter.KEY_ALIS, CryptoParameter.ALGORITHM)));

    }
}
