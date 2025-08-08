package com.netcracker.cloud.encryption.config.xml;

import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class XmlConfigIntegrationTest {
    private static final SecretKey DEFAULT_SECRET_KEY =
            new SecretKeySpec(Base64.decodeBase64("rhwh/TKdB9Hb6zpLBHW/mw=="), "AES");

    private XmlConfigurationSerializer xmlConfigurationAdapter;

    @TempDir
    private Path tmp;

    @BeforeEach
    void setUp() {
        xmlConfigurationAdapter =
                new XmlConfigurationSerializer(new DefaultConfigurationCryptoProvider(DEFAULT_SECRET_KEY));
    }

    @Test
    void testNotAvailableLoadConfigurationFromNotExistsFile() {
        assertThrows(
                RuntimeException.class,
                () -> xmlConfigurationAdapter.loadConfiguration(new File("notexistsfile")),
                "We should restrict loading config from not exists file"
        );
    }

    @Test
    void testFileToLoadConfigCanNotBeNull() {
        assertThrows(
                NullPointerException.class,
                () -> xmlConfigurationAdapter.loadConfiguration((File) null),
                "Null file that should contain configuration restrict contract"
        );
    }

    @Test
    void testEncryptionSubsystemLoadsCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getCryptoSubsystemConfig(), Matchers.notNullValue());
    }

    @Test
    void testEncryptionSubSystem_parsAlgorithm() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        assertThat("Algorithm should be pars from xml as is without any validation that they exists",
                encryptionConfiguration.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultAlgorithm(equalTo("MyJcaAlgorithm")));
    }

    @Test
    void testEncryptionSubSystem_parsKeyAlias() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        assertThat(
                "KeyAlias should be pars from xml as is without any validation that they key with it alias exists in keystore",
                encryptionConfiguration.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultKeyAlias(equalTo("MyKeyForMyJcaAlgorithm")));
    }

    @Test
    void testEncryptionSubSystem_parsKeyStoreName() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-crypto-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);

        assertThat(
                "KeyAlias should be pars from xml as is without any validation that they key with it alias exists in keystore",
                encryptionConfiguration.getCryptoSubsystemConfig(),
                CryptoConfigurationMatchers.defaultKeyAlias(equalTo("MyKeyForMyJcaAlgorithm")));
    }

    @Test
    void testEncryptionSubSystem_2waySerialize_checkAlgorithm() throws Exception {
        File file = tmp.resolve("test").toFile();

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
    void testEncryptionSubSystem_2waySerialize_checkKeyAlias() throws Exception {
        File file = tmp.resolve("test").toFile();


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
    void testEncryptionSubSystem_2waySerialize_checkKeystore() throws Exception {
        File file = tmp.resolve("test").toFile();

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
    void testKeyStoreLoadsCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);
        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(), Matchers.notNullValue());
    }

    @Test
    void testKeyStore_defaultKeystoreExists() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(),
                KeyStoreSubsystemConfigMatchers.defaultKeyStoreConfig(notNullValue()));
    }

    @Test
    void testKeyStore_defaultKeystoreParsCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(), KeyStoreSubsystemConfigMatchers
                        .defaultKeyStoreConfig(KeyStoreConfigMatchers.keyStoreIdentity(equalTo("defaultKeyStore"))));
    }

    @Test
    void testKeyStore_whenDefaultKeystoreNotSpecifyExplicitUseFirstKeyStore() throws Exception {
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
    void testKeyStoreFilled_listKeyStoreConfigNotEmpty() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        assertThat("When correct xml contain encryption subsystem after unmarshar they should exists like java object",
                encryptionConfiguration.getKeyStoreSubsystemConfig(),
                not(KeyStoreSubsystemConfigMatchers.emptyListKeyStoreConfigs()));
    }


    @Test
    void testLocalKeyStoreConfig_parsePathCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers
                .keyStoreLocation(equalTo("/u02/qubership/toms/u214_a2_6307/my_test_keystore.ks")));
    }


    @Test
    void testLocalKeyStoreConfig_parseTypeCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreType(equalTo("JSK")));
    }

    @Test
    void testLocalKeyStoreConfig_parsePasswordCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.getFirst(keyStores, null);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStorePassword(equalTo("123456")));
    }

    @Test
    void testLocalKeyStoreConfig_parseIsDeprecatedCorrect() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig firstKeyStore = (LocalKeystoreConfig) Iterables.get(keyStores, 2);

        assertThat(firstKeyStore, KeyStoreConfigMatchers.keyStoreIsDeprecated(equalTo(true)));
    }

    @Test
    void testLocalKeyStoreConfig_keysNotEmpty() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);

        assertThat(keyStoreWithKeys, not(KeyStoreConfigMatchers.emptyKeys()));
    }

    @Test
    void testLocalKeyStoreConfig_keyAliasParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 0);

        assertEquals("key1", keyConfig.getAlias());
    }

    @Test
    void testLocalKeyStoreConfig_keyIsNotDeprecatedByDefault() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 0);

        assertFalse(keyConfig.isDeprecated());
    }

    @Test
    void testLocalKeyStoreConfig_keyDeprecatedParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 1);

        assertTrue(keyConfig.isDeprecated());
    }

    @Test
    void testLocalKeyStoreConfig_keyPasswordParsedCorrectly() throws Exception {
        File cfg = getResource("/org/qubership/security/encryption/config/xml/full-filled-keystore-subsystem.xml");
        EncryptionConfiguration encryptionConfiguration = xmlConfigurationAdapter.loadConfiguration(cfg);

        System.out.println(encryptionConfiguration);
        List<KeystoreConfig> keyStores = encryptionConfiguration.getKeyStoreSubsystemConfig().getKeyStores();

        LocalKeystoreConfig keyStoreWithKeys = (LocalKeystoreConfig) Iterables.get(keyStores, 3);
        KeyConfig keyConfig = Iterables.get(keyStoreWithKeys.getKeys(), 0);

        assertEquals("123456", keyConfig.getPassword());
    }

    @Test
    void testLocalKeyStoreConfig_keyEmptyPasswordParsedCorrectly() throws Exception {
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
    void testKeyStoreSubSystem_2waySerialize_checkIdentity() throws Exception {
        File file = tmp.resolve("test").toFile();

        final String waitIdentity = "keyst";

        List<KeystoreConfig> keystores = List.of(
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
    void testKeyStoreSubSystem_2waySerialize_checkLocation() throws Exception {
        File file = tmp.resolve("test").toFile();

        final String location = "./tmp.ks";

        List<KeystoreConfig> keystores = List.of(
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
    void testKeyStoreSubSystem_2waySerialize_checkKeystoreType() throws Exception {
        File file = tmp.resolve("test").toFile();

        final String type = "myksType";

        List<KeystoreConfig> keystores = List.of(
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
    void testKeyStoreSubSystem_2waySerialize_checkKeystorePassword() throws Exception {
        File file = tmp.resolve("test").toFile();

        final String password = "superpassword";

        List<KeystoreConfig> keystores = List.of(
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
    void testKeyStoreSubSystem_2waySerialize_setDefaultKeystore() throws Exception {
        File file = tmp.resolve("test").toFile();

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

    @Test
    void testBeforeParseXmlTheyShouldBeValidateByXSD() throws Exception {
        File cfg =
                getResource("/org/qubership/security/encryption/config/xml/configuration-without-required-node.xml");

        assertThrows(
                IllegalConfiguration.class,
                () -> xmlConfigurationAdapter.loadConfiguration(cfg),
                "Not correct filled configuration should be ignore, and provider that allow load it should fail with correspond exception, "
                        + "because not correct configure security can lead to undefined server state. XSD it contract, that should be observed."
        );
    }

    @Test
    void testBeforeStoreConfigurationShouldBeProcessXSDValidation() {
        File file = tmp.resolve("test").toFile();
        EncryptionConfig config = new EncryptionConfig();
        config.setKeystoreSubsystemConfig(new KeyStoreSubsystemXmlConf());

        assertThrows(
                IllegalConfiguration.class,
                () -> xmlConfigurationAdapter.saveConfiguration(file, config),
                "Before save configuration they should be validate, because if we save not valid configuration they can be load after save, "
                        + "and code that save it can not learn about it, "
                        + "but if we validate all parameters before save, we can throws correspond exception if so of parameters configure not correct."
        );
    }

    @Test
    void testPasswordStoresInXmlInEncryptedForm() throws Exception {
        File file = tmp.resolve("test").toFile();

        String password = "{AES}noCryptedPassword";

        KeystoreConfig ks = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("KS-1")
                .setLocation("/u02/some.ks").setKeystoreType("myType").setPassword(password).build();

        KeystoreSubsystemConfig keystoreSubsystemConfig = createKeyStoreSubsystemConfig(ks);
        EncryptionConfiguration config = createConfigWithKSSubsystem(keystoreSubsystemConfig);

        writeConfig(file, config);

        String xml = Files.asCharSource(file, StandardCharsets.UTF_8).read();

        assertThat(
                "Password should be encrypted before save it to xml, because without it keystore can be stolen and all the keys in it compromised",
                xml, Matchers.not(Matchers.containsString(password)));
    }

    @Test
    void testNotEncryptedSecureDataByLoadingEncryptsAndStoreToXml() throws Exception {
        String plainTextPassword = "qwerty";
        File cfgFile = getResource(
                "/org/qubership/security/encryption/config/xml/filled-keystore-subsystem-without-default-keystore.xml");

        assertTrue(Files.asCharSource(cfgFile, StandardCharsets.UTF_8).read().contains(plainTextPassword));

        EncryptionConfiguration readConfiguration = xmlConfigurationAdapter.loadConfiguration(cfgFile);

        String xmlConfig = Files.asCharSource(cfgFile, StandardCharsets.UTF_8).read();

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
        String result = Files.asCharSource(file, StandardCharsets.UTF_8).read();
        System.out.println("Unmarshalling:\n" + result);
    }

    private File getResource(String resourceName) throws IOException {
        String data = IOUtils.toString(this.getClass().getResourceAsStream(resourceName), StandardCharsets.UTF_8);
        System.out.println("Read configuration:\n" + data);
        File targetFile = tmp.resolve("test").toFile();
        Files.asCharSink(targetFile, StandardCharsets.UTF_8).write(data);
        return targetFile;
    }
}

