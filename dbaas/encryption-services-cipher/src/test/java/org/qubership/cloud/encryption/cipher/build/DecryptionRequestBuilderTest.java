package org.qubership.cloud.encryption.cipher.build;

import org.qubership.cloud.encryption.cipher.DecryptionRequest;
import org.hamcrest.Matchers;
import org.junit.Test;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class DecryptionRequestBuilderTest {
    @SuppressWarnings("unused")
    @Test(expected = NullPointerException.class)
    public void testEncryptedTextByteArrayCanNotBeNull() throws Exception {
        DecryptionRequest result = DecryptionRequestBuilder.createBuilder().build();
        fail("EncryptionRequestBuilder have required field it plain text without it build method should fail");
    }

    @Test(expected = NullPointerException.class)
    public void testUseBuilderAsResultAndGetRequiredEncryptedTextLeadToNPE() throws Exception {
        DecryptionRequest result = (DecryptionRequest) DecryptionRequestBuilder.createBuilder();

        assertThat(result.getEncryptedText(), Matchers.notNullValue());
        fail("empty array it empty string, but in case if value was not specify we should fail with NPE");
    }
}
