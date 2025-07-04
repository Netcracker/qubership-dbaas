package org.qubership.cloud.encryption.cipher.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class DecryptionRequestBuilderTest {
    @SuppressWarnings("unused")
    @Test
    void testEncryptedTextByteArrayCanNotBeNull() {
        assertThrows(
                NullPointerException.class,
                () -> DecryptionRequestBuilder.createBuilder().build(),
                "EncryptionRequestBuilder have required field it plain text without it build method should fail"
        );
    }
}
