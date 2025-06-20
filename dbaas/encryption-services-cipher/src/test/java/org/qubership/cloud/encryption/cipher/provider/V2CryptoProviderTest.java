package org.qubership.cloud.encryption.cipher.provider;

import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.xml.ConfigurationBuildersFactory;
import org.qubership.cloud.encryption.key.KeyStore;
import org.qubership.cloud.encryption.key.KeyStoreStub;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Collections;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class V2CryptoProviderTest {
    CryptoProvider provider;
    private KeyStore keyStore;

    @Before
    public void setUp() throws Exception {
        CryptoSubsystemConfig config = new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder()
                .setDefaultAlgorithm("AES").setDefaultKeyAlias("AESDefaultKey").build();

        final SecretKey defaultKey = KeyGenerator.getInstance(config.getDefaultAlgorithm().get()).generateKey();

        keyStore = new KeyStoreStub(Collections.singletonMap(config.getDefaultKeyAlias().get(), defaultKey));
        provider = new V2CryptoProvider(keyStore, config);
    }

    @Test
    public void testCheckSuitableFormat() throws Exception {
        String template = "{v2c}{DES}{secKeyAlias}{95ec+8MrCLM=}";

        boolean result = provider.isKnowEncryptedFormat(template);

        assertThat("Was check format that return it provider by default:" + template, result, equalTo(true));
    }

    @Test
    public void testCheckSuitableFormat_withDefaultAlgorithm() throws Exception {
        String template =
                "{v2c}{AES/ECB/PKCS5Padding}{AESDefaultKey}{tE6Y9niEE3pMT6i5IHp5urF8Gm68PZLiZbr/xONDptAGz5tWLtp5rnrrW1762exa0N+7IUMC075P8P9n7m9DCpKebsfKhUyNFcETbDMNTWXw=}";

        boolean result = provider.isKnowEncryptedFormat(template);

        assertThat("Was check format that return it provider by default:" + template, result, equalTo(true));
    }

    @Test
    public void testCheckNotSuitableFormat() throws Exception {
        String template = "{new}{v2c}{DES}{secKeyAlias}{95ec+8MrCLM=}";

        boolean result = provider.isKnowEncryptedFormat(template);
        assertThat("Suitable format {v2c}{DES}{secKeyAlias}{95ec+8MrCLM=} but for check was use " + template, result,
                equalTo(false));
    }

    @Test
    public void testTomsFormatTemplateNotSuitable() throws Exception {
        String template = "{DES}JUW5jgRqH0w=";

        boolean result = provider.isKnowEncryptedFormat(template);
        assertThat("Suitable format {v2c}{algorithm}{secKeyAlias}{base64(encryptedData)} but for check was use "
                + template, result, equalTo(false));
    }

    @Test
    public void testNotSuitableFormatWhenMidlePartContainAdditionalInfo() throws Exception {
        String template = "{v2c}{DES}{secKeyAlias}{SOME_PARAMETER}{95ec+8MrCLM=}";

        boolean result = provider.isKnowEncryptedFormat(template);
        assertThat("Suitable format {v2c}{algorithm}{secKeyAlias}{base64(encryptedData)} but for check was use "
                + template, result, equalTo(false));
    }


}
