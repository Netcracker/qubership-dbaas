package org.qubership.cloud.encryption.cipher.result;

import org.qubership.cloud.encryption.cipher.EncryptResult;
import org.qubership.cloud.encryption.cipher.provider.EncryptedData;
import org.qubership.cloud.encryption.cipher.provider.EncryptedDataBuilder;
import org.qubership.cloud.encryption.key.ImmutableAliasedKey;
import org.hamcrest.Matchers;
import org.junit.Test;

import javax.crypto.KeyGenerator;
import java.util.Arrays;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class NotTemplateEncryptResultTest {

    @SuppressWarnings("unused")
    @Test(expected = UnsupportedOperationException.class)
    public void testUnsupportedGetAsTemplate() throws Exception {
        EncryptedData data = new EncryptedDataBuilder().setUsedAlgorithm("AES")
                .setUsedKey(new ImmutableAliasedKey(KeyGenerator.getInstance("AES").generateKey()))
                .setEncryptedText(new byte[] { 0x1, 0x2 }).build();

        EncryptResult result = new NotTemplateEncryptResult(data);

        String template = result.getResultAsEncryptionServiceTemplate();
        fail("Object NotTemplateEncryptResult not support templates by contract");
    }

    @Test
    public void testBytArraysAvailableAsIs() throws Exception {
        byte[] cryptedArray = new byte[] { (byte) 0x1, (byte) 0x3, (byte) 0x5, (byte) 0x10 };

        EncryptedData data = new EncryptedDataBuilder().setUsedAlgorithm("AES")
                .setUsedKey(new ImmutableAliasedKey(KeyGenerator.getInstance("AES").generateKey()))
                .setEncryptedText(cryptedArray).build();

        EncryptResult encryptResult = new NotTemplateEncryptResult(data);

        assertThat("We can't lost some bytes from cryptedResult because without it we can't decrypt message",
                Arrays.equals(cryptedArray, encryptResult.getResultAsByteArray()), Matchers.equalTo(true));
    }
}
