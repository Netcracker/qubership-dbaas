package com.netcracker.cloud.encryption.cipher.build;

import org.junit.jupiter.api.Test;
import org.qubership.cloud.encryption.cipher.DecryptionRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DecryptionRequestBuilderTest {
    @Test
    void testEncryptedTextByteArrayCanNotBeNull() {
        assertThrows(
                NullPointerException.class,
                () -> DecryptionRequestBuilder.createBuilder().build(),
                "EncryptionRequestBuilder have required field it plain text without it build method should fail"
        );
    }

    @Test
    void testUseBuilderAsResultAndGetRequiredEncryptedTextLeadToNPE() {
        DecryptionRequest result = (DecryptionRequest) DecryptionRequestBuilder.createBuilder();
        assertThrows(
                NullPointerException.class,
                result::getEncryptedText,
                "empty array it empty string, but in case if value was not specify we should fail with NPE"
        );
    }
}
