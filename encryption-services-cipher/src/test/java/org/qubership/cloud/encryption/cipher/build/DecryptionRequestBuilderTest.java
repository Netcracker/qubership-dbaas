package org.qubership.cloud.encryption.cipher.build;

import org.junit.jupiter.api.Test;
import org.qubership.cloud.encryption.cipher.DecryptionRequest;
import org.hamcrest.Matchers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

public class DecryptionRequestBuilderTest {
    @SuppressWarnings("unused")
    @Test
    void testEncryptedTextByteArrayCanNotBeNull() {
        DecryptionRequest result = DecryptionRequestBuilder.createBuilder().build();
        fail("EncryptionRequestBuilder have required field it plain text without it build method should fail");
    }

    @Test
    void testUseBuilderAsResultAndGetRequiredEncryptedTextLeadToNPE() {
        DecryptionRequest result = (DecryptionRequest) DecryptionRequestBuilder.createBuilder();

        assertThat(result.getEncryptedText(), Matchers.notNullValue());
        fail("empty array it empty string, but in case if value was not specify we should fail with NPE");
    }
}
