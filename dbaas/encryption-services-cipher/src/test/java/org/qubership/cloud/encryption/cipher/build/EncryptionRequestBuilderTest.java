package org.qubership.cloud.encryption.cipher.build;

import org.junit.jupiter.api.Test;
import org.qubership.cloud.encryption.cipher.EncryptionRequest;
import org.hamcrest.Matchers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

public class EncryptionRequestBuilderTest {
    @SuppressWarnings("unused")
    @Test
    void testPlainTextByteArrayCanNotBeNull() {
        assertThrows(NullPointerException.class, () -> EncryptionRequestBuilder.createBuilder().build());
        fail("EncryptionRequestBuilder have required field it plain text without it build method should fail");
    }

    @Test
    void testUseBuilderAsResultAndGetRequiredPlainTextLeadToNPE() {
        EncryptionRequest result = (EncryptionRequest) EncryptionRequestBuilder.createBuilder();

        assertThat(result.getPlainText(), Matchers.notNullValue());
        fail("empty array it empty string, but in case if value was not specify we should fail with NPE");
    }
}
