package com.netcracker.cloud.encryption.cipher.build;

import org.junit.jupiter.api.Test;
import com.netcracker.cloud.encryption.cipher.EncryptionRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionRequestBuilderTest {
    @Test
    void testPlainTextByteArrayCanNotBeNull() {
        assertThrows(
                NullPointerException.class,
                () -> EncryptionRequestBuilder.createBuilder().build(),
                "EncryptionRequestBuilder have required field it plain text without it build method should fail"
        );
    }

    @Test
    void testUseBuilderAsResultAndGetRequiredPlainTextLeadToNPE() {
        EncryptionRequest result = (EncryptionRequest) EncryptionRequestBuilder.createBuilder();
        assertThrows(
                NullPointerException.class,
                result::getPlainText,
                "empty array it empty string, but in case if value was not specify we should fail with NPE"
        );
    }
}
