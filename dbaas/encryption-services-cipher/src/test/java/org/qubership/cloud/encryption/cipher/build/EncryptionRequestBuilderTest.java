package org.qubership.cloud.encryption.cipher.build;

import org.qubership.cloud.encryption.cipher.EncryptionRequest;
import org.hamcrest.Matchers;
import org.junit.Test;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class EncryptionRequestBuilderTest {
    @SuppressWarnings("unused")
    @Test(expected = NullPointerException.class)
    public void testPlainTextByteArrayCanNotBeNull() throws Exception {
        EncryptionRequest result = EncryptionRequestBuilder.createBuilder().build();
        fail("EncryptionRequestBuilder have required field it plain text without it build method should fail");
    }

    @Test(expected = NullPointerException.class)
    public void testUseBuilderAsResultAndGetRequiredPlainTextLeadToNPE() throws Exception {
        EncryptionRequest result = (EncryptionRequest) EncryptionRequestBuilder.createBuilder();

        assertThat(result.getPlainText(), Matchers.notNullValue());
        fail("empty array it empty string, but in case if value was not specify we should fail with NPE");
    }
}
