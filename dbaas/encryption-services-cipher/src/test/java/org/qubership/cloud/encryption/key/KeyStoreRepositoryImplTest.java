package org.qubership.cloud.encryption.key;

import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.ConfigurationBuildersFactory;
import org.qubership.cloud.encryption.config.xml.DefaultConfigurationCryptoProvider;
import org.qubership.cloud.encryption.config.xml.XmlConfigurationSerializer;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.RemoteKeystoreXmlConf;
import org.qubership.cloud.encryption.key.exception.IllegalKeystoreConfigurationException;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.annotation.Nonnull;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@SuppressWarnings({ "unchecked", "unused" })
public class KeyStoreRepositoryImplTest {
    private ConfigurationParser parser;
    private TemporaryFolder tmp;

    @Before
    public void setUp() throws Exception {
        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();
        parser = new XmlConfigurationSerializer(new DefaultConfigurationCryptoProvider(secretKey));

        tmp = new TemporaryFolder();
        tmp.create();
    }

    @After
    public void tearDown() throws Exception {
        tmp.delete();
        tmp = null;
    }

    @Test
    public void testEmptyConfigurationParseCorrectly() throws Exception {
        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder().build();

        KeyStoreRepository result = new KeyStoreRepositoryImpl(config);
        assertThat("Configuration without keystores it's OK configuration", result, Matchers.notNullValue());
    }

    @Test(expected = NullPointerException.class)
    public void testNullLikeConfigurationCanNotBeSpecify() throws Exception {
        KeyStoreRepository result = new KeyStoreRepositoryImpl(null);
        fail("It restrict contract");
    }

    @Test
    public void testDefaultKeyStoreNotExistsInEmptyConfigureKeystoreRepository() throws Exception {
        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder().build();

        KeyStoreRepository result = new KeyStoreRepositoryImpl(config);

        assertThat("When keystores not configure, repostiory always should return null", result.getDefaultKeystore(),
                Matchers.nullValue());
    }

    @Test
    public void testDefaultKeystoreDefinesCorrectInCaseWhenManyKeystoresConfigure() throws Exception {
        final String defaultKSIdentity = "ks-def";

        final LocalKeystoreConfig firstConfig = generateLocalKeystoreConfig(defaultKSIdentity);
        final LocalKeystoreConfig secondConfig = generateLocalKeystoreConfig("ks-2");
        final LocalKeystoreConfig threeConfig = generateLocalKeystoreConfig("ks-3");

        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(Arrays.asList(threeConfig, firstConfig, secondConfig)).setDefaultKeyStore(firstConfig)
                .build();


        KeyStoreRepository repository = new KeyStoreRepositoryImpl(config);

        String identity = repository.getDefaultKeystore().getIdentity();

        assertThat(
                "Keystore that specify in configuration like default should be available via correspond method on repository",
                identity, equalTo(defaultKSIdentity));
    }

    @Test
    public void testGetKeyStoreByTheyIdentity() throws Exception {
        final String defaultKSIdentity = "ks-def";

        final LocalKeystoreConfig firstConfig = generateLocalKeystoreConfig(defaultKSIdentity);
        final LocalKeystoreConfig secondConfig = generateLocalKeystoreConfig("ks-2");
        final LocalKeystoreConfig threeConfig = generateLocalKeystoreConfig("ks-3");

        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(Arrays.asList(threeConfig, firstConfig, secondConfig)).setDefaultKeyStore(firstConfig)
                .build();


        KeyStoreRepository repository = new KeyStoreRepositoryImpl(config);

        KeyStore findKeystore = repository.getKeyStoreByIdentity("ks-2");

        assertThat(
                "In configuration was describe 3 keystores and each have unique name, so, we should can lockup it by it unique name",
                findKeystore.getIdentity(), equalTo("ks-2"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testParseRemoveKeyStore() throws Exception {
        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(Arrays.asList(new RemoteKeystoreXmlConf())).build();

        KeyStoreRepository repository = new KeyStoreRepositoryImpl(config);
        fail("Remote keystore not implemented yet");
    }

    @Test(expected = IllegalKeystoreConfigurationException.class)
    public void testParseNotCorrectFilledKeystoreLeadToFailAllKeystores() throws Exception {
        LocalKeystoreConfig notValidConfig = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("ks2")
                .setPassword("123").setKeystoreType("notExistsType").setLocation("/dev/null").build();

        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(Arrays.asList(notValidConfig)).build();

        KeyStoreRepository repository = new KeyStoreRepositoryImpl(config);

        fail("When one ofe keystore have not correct parameters should fail configure all another keystores, "
                + "because if we ignore it exception, it lead to proble in runtime that will be detected after a while");
    }

    private LocalKeystoreConfig generateLocalKeystoreConfig(@Nonnull String identity) throws Exception {
        final String keystoreType = "JCEKS";
        final String ksPass = "someKsPassword";
        final String location = tmp.newFile("test" + System.nanoTime() + ".ks").getAbsolutePath();

        final String keyAlias = "mySecretKey";

        java.security.KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();

        keyStore.setEntry(keyAlias, new java.security.KeyStore.SecretKeyEntry(secretKey),
                new java.security.KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        return new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder(identity).setLocation(location)
                .setPassword(ksPass).setKeystoreType(keystoreType).build();
    }
}
