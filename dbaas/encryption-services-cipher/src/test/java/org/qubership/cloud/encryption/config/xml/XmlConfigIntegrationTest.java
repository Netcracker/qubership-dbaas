package org.qubership.cloud.encryption.config.xml;

import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import org.qubership.cloud.encryption.config.EncryptionConfiguration;
import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.exception.IllegalConfiguration;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.matchers.CryptoConfigurationMatchers;
import org.qubership.cloud.encryption.config.xml.matchers.KeyStoreConfigMatchers;
import org.qubership.cloud.encryption.config.xml.matchers.KeyStoreSubsystemConfigMatchers;
import org.qubership.cloud.encryption.config.xml.pojo.conf.EncryptionConfig;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.KeyStoreSubsystemXmlConf;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class XmlConfigIntegrationTest {
    private static final SecretKey DEFAULT_SECRET_KEY =
            new SecretKeySpec(Base64.decodeBase64("rhwh/TKdB9Hb6zpLBHW/mw=="), "AES");

    private XmlConfigurationSerializer xmlConfigurationAdapter;

    private TemporaryFolder tmp;

    @Before
    public void setUp() throws Exception {
        xmlConfigurationAdapter =
                new XmlConfigurationSerializer(new DefaultConfigurationCryptoProvider(DEFAULT_SECRET_KEY));
        tmp = new TemporaryFolder();
        tmp.create();
    }

    @After
    public void tearDown() throws Exception {
        tmp.delete();
        tmp = null;
    }

    // todo fix
    @Test(expected = RuntimeException.class)
    public void testNotAvailableLoadConfigurationFromNotExistsFile() throws Exception {
        xmlConfigurationAdapter.loadConfiguration(new File("notexistsfile"));
        fail("We should restrict loading config from not exists file");
    }

    @Test(expected = NullPointerException.class)
    public void testFileToLoadConfigCanNotBeNull() throws Exception {
        xmlConfigurationAdapter.loadConfiguration((File) null);
        fail("Null file that should contain configuration restrict contract");
    }

    @Test
    public void testEncryptionSubsystemLoadsCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getCryptoSubsystemConfig(), Matchers.notNullValue());
    }

    @Test
    public void testEncryptionSubSystem_parsAlgorithm() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        assertThat("Algorithm should be pars from xml as is without any validation that they exists",
                encryptionConfiguration.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultAlgorithm(equalTo("MyJcaAlgorithm")));
    }

    @Test
    public void testEncryptionSubSystem_parsKeyAlias() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        assertThat(
                "KeyAlias should be pars from xml as is without any validation that they key with it alias exists in keystore",
                encryptionConfiguration.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultKeyAlias(equalTo("MyKeyForMyJcaAlgorithm")));
    }

    @Test
    public void testEncryptionSubSystem_parsKeyStoreName() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);

        assertThat(
                "KeyAlias should be pars from xml as is without any validation that they key with it alias exists in keystore",
                encryptionConfiguration.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultKeyAlias(equalTo("MyKeyForMyJcaAlgorithm")));
    }

    @Test
    public void testEncryptionSubSystem_2waySerialize_checkAlgorithm() throws Exception {
        File file = tmp.newFile();

        final String waitAlgorithmName = "DES";

        CryptoSubsystemConfig cryptoSubsystemConfig = new ConfigurationBuildersFactory()
                .getCryptoSubsystemConfigBuilder().setDefaultAlgorithm(waitAlgorithmName).build();

        EncryptionConfiguration config = new ConfigurationBuildersFactory().getConfigurationBuilder()
                .setCryptoSubsystemConfig(cryptoSubsystemConfig).build();

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        assertThat(result.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultAlgorithm(equalTo(waitAlgorithmName)));
    }

    @Test
    public void testEncryptionSubSystem_2waySerialize_checkKeyAlias() throws Exception {
        File file = tmp.newFile();

        final String waitValue = "myKey";

        CryptoSubsystemConfig cryptoSubsystemConfig = new ConfigurationBuildersFactory()
                .getCryptoSubsystemConfigBuilder().setDefaultKeyAlias(waitValue).build();

        EncryptionConfiguration config = new ConfigurationBuildersFactory().getConfigurationBuilder()
                .setCryptoSubsystemConfig(cryptoSubsystemConfig).build();

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        assertThat(result.getCryptoSubsystemConfig(), CryptoConfigurationMatchers.defaultKeyAlias(equalTo(waitValue)));
    }

    @Test
    public void testEncryptionSubSystem_2waySerialize_checkKeystore() throws Exception {
        File file = tmp.newFile();

        final String waitValue = "myKeyStore";

        CryptoSubsystemConfig cryptoSubsystemConfig =
                new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder().setKeyStoreName(waitValue).build();

        EncryptionConfiguration config = new ConfigurationBuildersFactory().getConfigurationBuilder()
                .setCryptoSubsystemConfig(cryptoSubsystemConfig).build();

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        assertThat(result.getCryptoSubsystemConfig(), CryptoConfigurationMatchers.keyStoreName(equalTo(waitValue)));
    }

    @Test
    public void testKeyStoreLoadsCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);
        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(), Matchers.notNullValue());
    }

    @Test
    public void testKeyStore_defaultKeystoreExists() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(),
                KeyStoreSubsystemConfigMatchers.defaultKeyStoreConfig(notNullValue()));
    }

    @Test
    public void testKeyStore_defaultKeystoreParsCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(), KeyStoreSubsystemConfigMatchers
                        .defaultKeyStoreConfig(KeyStoreConfigMatchers.keyStoreIdentity(equalTo("defaultKeyStore"))));
    }

    @Test
    public void testKeyStore_whenDefaultKeystoreNotSpecifyExplicitUseFirstKeyStore() throws Exception {
        File cfg = getResource(
                "/org/qubership/security/encryption/config/xml/filled-keystore-subsystem-without-default-keystore.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat(
                "When default keystore not specify bu contract we should get first keystore config and use it like default",
                encryptionConfiguration.getKeyStoreSubsystemConfig(), KeyStoreSubsystemConfigMatchers
                        .defaultKeyStoreConfig(KeyStoreConfigMatchers.keyStoreIdentity(equalTo("keystore-1"))));
    }

    @Test
    public void testKeyStoreFilled_listKeyStoreConfigNotEmpty() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(),
                not(KeyStoreSubsystemConfigMatchers.emptyListKeyStoreConfigs()));
    }


    @Test
    public void testLocalKeyStoreConfig_parsePathCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers
                .keyStoreLocation(equalTo("/u02/qubership/toms/u214_a2_6307/my_test_keystore.ks")));
    }


    @Test
    public void testLocalKeyStoreConfig_parseTypeCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreType(equalTo("JSK")));
    }

    @Test
    public void testLocalKeyStoreConfig_parsePasswordCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStorePassword(equalTo("123456")));
    }

    @Test
    public void testLocalKeyStoreConfig_parseIsDeprecatedCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.get(keyStores, 2);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreIsDeprecated(equalTo(true)));
    }

    @Test
    public void testLocalKeyStoreConfig_keysNotEmpty() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);

        assertThat(keyStoreWithKeys, not(KeyStoreConfigMatchers.emptyKeys()));
    }

    @Test
    public void testLocalKeyStoreConfig_keyAliasParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 0);

        assertEquals("key1", keyConfig.getAlias() );
    }

    @Test
    public void testLocalKeyStoreConfig_keyIsNotDeprecatedByDefault() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 0);

        assertFalse(keyConfig.isDeprecated());
    }

    @Test
    public void testLocalKeyStoreConfig_keyDeprecatedParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 1);

        assertTrue(keyConfig.isDeprecated());
    }

    @Test
    public void testLocalKeyStoreConfig_keyPasswordParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 0);

        assertEquals(keyConfig.getPassword(), "123456");
    }

    @Test
    public void testLocalKeyStoreConfig_keyEmptyPasswordParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 2);
        System.out.println(keyConfig.getPassword());
        assertThat(keyConfig.getPassword(), isEmptyString());
    }

    @Test
    public void testKeyStoreSubSystem_2waySerialize_checkIdentity() throws Exception {
        File file = tmp.newFile();

        final String waitIdentity = "keyst";

        List<KeystoreConfig> keystores = Arrays.<KeystoreConfig>asList(

                new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder(waitIdentity)
                        .setLocation("./keystore.ks").setKeystoreType("jcs").setPassword("123").build());


        KeystoreSubsystemConfig keystoreSubsystemConfig =
                new ConfigurationBuildersFactory().getKeystoreConfigBuilder().setKeyStores(keystores).build();

        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        List<KeystoreConfig> keyStores = result.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreIdentity(equalTo(waitIdentity)));
    }

    @Test
    public void testKeyStoreSubSystem_2waySerialize_checkLocation() throws Exception {
        File file = tmp.newFile();

        final String location = "./tmp.ks";

        List<KeystoreConfig> keystores = Arrays.<KeystoreConfig>asList(

                new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-1").setLocation(location)
                        .setKeystoreType("jcs")
                        .setPassword(
                                "asdasfq1fasvaegFOQY)*G!GUGH E+@GHAAS{FP)!+#GASBFAS{G@#+)G AGBQ*WEgAFGQgASFF+!@_#GHASDOLGHQA")
                        .build());

        KeystoreSubsystemConfig keystoreSubsystemConfig =
                new ConfigurationBuildersFactory().getKeystoreConfigBuilder().setKeyStores(keystores).build();

        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        List<KeystoreConfig> keyStores = result.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreLocation(equalTo(location)));
    }

    @Test
    public void testKeyStoreSubSystem_2waySerialize_checkKeystoreType() throws Exception {
        File file = tmp.newFile();

        final String type = "myksType";

        List<KeystoreConfig> keystores = Arrays.<KeystoreConfig>asList(

                new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-1").setLocation("/u02/some.ks")
                        .setKeystoreType(type).setPassword("{AES}noCryptedPassword").build());

        KeystoreSubsystemConfig keystoreSubsystemConfig =
                new ConfigurationBuildersFactory().getKeystoreConfigBuilder().setKeyStores(keystores).build();

        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        List<KeystoreConfig> keyStores = result.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreType(equalTo(type)));
    }

    @Test
    public void testKeyStoreSubSystem_2waySerialize_checkKeystorePassword() throws Exception {
        File file = tmp.newFile();

        final String password = "superpassword";

        List<KeystoreConfig> keystores = Arrays.<KeystoreConfig>asList(

                new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-1").setLocation("/u02/some.ks")
                        .setKeystoreType("jks").setPassword(password).build());

        KeystoreSubsystemConfig keystoreSubsystemConfig =
                new ConfigurationBuildersFactory().getKeystoreConfigBuilder().setKeyStores(keystores).build();

        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        List<KeystoreConfig> keyStores = result.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStorePassword(equalTo(password)));
    }

    @Test
    public void testKeyStoreSubSystem_2waySerialize_setDefaultKeystore() throws Exception {
        File file = tmp.newFile();

        final String password = "superpassword";

        KeystoreConfig firstKS = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-1")
                .setLocation("/u02/some.ks").setKeystoreType("jks").setPassword(password).build();

        KeystoreConfig secondKS = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-2")
                .setLocation("/u02/some2.ks").setKeystoreType("jks").setPassword("super password").build();

        List<KeystoreConfig> keystores = Arrays.asList(firstKS, secondKS);

        KeystoreSubsystemConfig keystoreSubsystemConfig = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(keystores).setDefaultKeyStore(secondKS).build();

        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        EncryptionConfiguration result = xmlConfigurationAdapter.loadConfiguration(file);

        assertThat(result.getKeyStoreSubsystemConfig(), KeyStoreSubsystemConfigMatchers.defaultKeyStoreConfig(
                KeyStoreConfigMatchers.keyStoreIdentity(equalTo(secondKS.getKeystoreIdentifier()))));
    }

    @Test(expected = IllegalConfiguration.class)
    public void testBeforeParseXmlTheyShouldBeValidateByXSD() throws Exception {
        File cfg =
                getResource("/org/qubership/security/encryption/config/xml/configuration-without-required-node.xml");

        EncryptionConfiguration loadedCfg = xmlConfigurationAdapter.loadConfiguration(cfg);

        fail("Not correct filled configuration should be ignore, and provider that allow load it should fail with correspond exception, "
                + "because not correct configure security can lead to undefined server state. XSD it contract, that should be observed. "
                + "Was load result" + loadedCfg);
    }

    @Test(expected = IllegalConfiguration.class)
    public void testBeforeStoreConfigurationShouldBeProcessXSDValidation() throws Exception {
        final File file = tmp.newFile();
        EncryptionConfig config = new EncryptionConfig();
        config.setKeystoreSubsystemConfig(new KeyStoreSubsystemXmlConf());

        xmlConfigurationAdapter.saveConfiguration(file, config);

        String result = Files.toString(file, Charset.forName("UTF-8"));

        fail("Before save configuration they should be validate, because if we save not valid configuration they can be load after save, "
                + "and code that save it can not learn about it, "
                + "but if we validate all parameters before save, we can throws correspond exception if so of parameters configure not correct. Unmarshalling result:\n"
                + result);
    }

    @Test
    public void testPasswordStoresInXmlInEncryptedForm() throws Exception {
        File file = tmp.newFile();

        String password = "{AES}noCryptedPassword";

        KeystoreConfig ks = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-1")
                .setLocation("/u02/some.ks").setKeystoreType("myType").setPassword(password).build();

        KeystoreSubsystemConfig keystoreSubsystemConfig = createKeyStoreSubsystemConfig(ks);
        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        String xml = Files.toString(file, Charset.forName("UTF-8"));

        assertThat(
                "Password should be encrypted before save it to xml, because without it keystore can be stolen and all the keys in it compromised",
                xml, Matchers.not(Matchers.containsString(password)));
    }

    @Test
    public void testNotEncryptedSecureDataByLoadingEncryptsAndStoreToXml() throws Exception {
        String plainTextPassword = "qwerty";
        File cfgFile = getResource(
                "/org/qubership/security/encryption/config/xml/filled-keystore-subsystem-without-default-keystore.xml");

        assert Files.toString(cfgFile, Charset.forName("UTF-8")).contains(plainTextPassword);

        EncryptionConfiguration readConfiguration = xmlConfigurationAdapter.loadConfiguration(cfgFile);

        String xmlConfig = Files.toString(cfgFile, Charset.forName("UTF-8"));

        System.out.println("Lazy encryption parameter encryption:\n" + xmlConfig);

        assertThat(
                "During load configuration all secure parameters should be encrypted in configuration file, "
                        + "if so parameter stores like plain text, they should be crypted and store again to file "
                        + "- it forks like asynchonize encryption. Readed configuration: " + readConfiguration,
                xmlConfig, not(containsString(plainTextPassword)));
    }

    private EncryptionConfiguration createConfigWithKSSubsystem(KeystoreSubsystemConfig keystoreSubsystemConfig) {
        return new ConfigurationBuildersFactory().getConfigurationBuilder()
                .setKeystoreSubsystemConfig(keystoreSubsystemConfig).build();
    }

    private KeystoreSubsystemConfig createKeyStoreSubsystemConfig(KeystoreConfig... keystores) {
        return new ConfigurationBuildersFactory().getKeystoreConfigBuilder().setKeyStores(Arrays.asList(keystores))
                .build();
    }

    private void writeConfig(File file, EncryptionConfiguration configuration) throws IOException {
        xmlConfigurationAdapter.saveConfiguration(file, configuration);
        String result = Files.toString(file, Charset.forName("UTF-8"));
        System.out.println("Unmarshaling:\n" + result);
    }

    private File getResource(String resourceName) throws IOException {
        String data = IOUtils.toString(this.getClass().getResourceAsStream(resourceName));
        System.out.println("Read configuration:\n" + data);
        File targetFile = tmp.newFile();
        Files.write(data, targetFile, Charset.forName("UTF-8"));
        return targetFile;
    }
}

