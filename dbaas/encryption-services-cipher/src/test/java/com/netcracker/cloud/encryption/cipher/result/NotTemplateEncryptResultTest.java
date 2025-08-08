package com.netcracker.cloud.encryption.cipher.result;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.qubership.cloud.encryption.cipher.EncryptResult;
import org.qubership.cloud.encryption.cipher.provider.EncryptedData;
import org.qubership.cloud.encryption.cipher.provider.EncryptedDataBuilder;
import org.qubership.cloud.encryption.key.ImmutableAliasedKey;

import javax.crypto.KeyGenerator;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;


class NotTemplateEncryptResultTest {

    @SuppressWarnings("unused")
    @Test
    void testUnsupportedGetAsTemplate() throws Exception {
        EncryptedData data = new EncryptedDataBuilder().setUsedAlgorithm("AES")
                .setUsedKey(new ImmutableAliasedKey(KeyGenerator.getInstance("AES").generateKey()))
                .setEncryptedText(new byte[]{0x1, 0x2}).build();

        EncryptResult result = new NotTemplateEncryptResult(data);

        assertThrows(
                UnsupportedOperationException.class,
                result::getResultAsEncryptionServiceTemplate,
                "Object NotTemplateEncryptResult not support templates by contract"
        );
    }

    @Test
    void testBytArraysAvailableAsIs() throws Exception {
        byte[] cryptedArray = new byte[]{(byte) 0x1, (byte) 0x3, (byte) 0x5, (byte) 0x10};

        EncryptedData data = new EncryptedDataBuilder().setUsedAlgorithm("AES")
                .setUsedKey(new ImmutableAliasedKey(KeyGenerator.getInstance("AES").generateKey()))
                .setEncryptedText(cryptedArray).build();

        EncryptResult encryptResult = new NotTemplateEncryptResult(data);

        assertThat("We can't lost some bytes from cryptedResult because without it we can't decrypt message",
                Arrays.equals(cryptedArray, encryptResult.getResultAsByteArray()), Matchers.equalTo(true));
    }
}
