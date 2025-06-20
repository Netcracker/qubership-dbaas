package org.qubership.cloud.encryption.key;

import org.qubership.cloud.encryption.cipher.exception.BadKeyPasswordException;
import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.ConfigurationBuildersFactory;
import org.qubership.cloud.encryption.config.xml.DefaultConfigurationCryptoProvider;
import org.qubership.cloud.encryption.config.xml.XmlConfigurationSerializer;
import org.qubership.cloud.encryption.key.exception.IllegalKeystoreConfigurationException;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@SuppressWarnings("unused")
public class LocalKeyStoreTest {
    private ConfigurationParser parser;
    private TemporaryFolder tmp;

    private static final String keystoreType = "JCEKS";
    private static final String ksPass = "someKsPassword";
    private static final String keyAlias = "mySecretKey";
    private static final String keyPassword = "myKeyPassword";
    private static final String algorithm = "AES";

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

    @Test(expected = NullPointerException.class)
    public void testNullConfigurationNotAvailable() throws Exception {
        LocalKeyStore keyStore = new LocalKeyStore(null);
        fail("it restrict contract");
    }

    @Test
    public void testKeyAvailableFromKeystore() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        SecretKey findKey = localKeyStore.getKeyByAlias(keyAlias, SecretKey.class);

        assertThat("Correct loaded keystore should contain key that was stores in it", findKey,
                Matchers.notNullValue());
    }

    @Test
    public void testNullAsResultFindKeyByAliasWhenTheyAbsetnInKeystore() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        SecretKey findKey = localKeyStore.getKeyByAlias(keyAlias, SecretKey.class);

        assertThat("If by alias not find in repository correspond key, by contract we should return null", findKey,
                Matchers.nullValue());
    }

    @Test
    public void testFindKeyByAliasAndInterface() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        PrivateKey findKey = localKeyStore.getKeyByAlias(keyAlias, PrivateKey.class);

        assertThat("If find in keystore key have different type on requested we should return null like result",
                findKey, Matchers.nullValue());
    }

    @Test(expected = IllegalKeystoreConfigurationException.class)
    public void testThrowReadableExceptionIfConfigurationIllegal() throws Exception {
        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("MyBadKs")
                .setLocation("/u02/ks.ks").setPassword("123").setKeystoreType("NotExistsTypes").build();


        LocalKeyStore localKeystore = new LocalKeyStore(config);

        fail("Illegal configuration shoul lead to correspond exception, if we pars configuration in asynchron "
                + "we can get runtime exception that will be difficult detect");

    }

    @Test
    public void testDeprecatedKeyStore() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setDeprecated(true).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        AliasedKey findKey = localKeyStore.getAliasedKey(keyAlias);

        assertThat("Correct loaded keystore should contain key that was stores in it", findKey,
                Matchers.notNullValue());

        assertThat("Key from deprecated keystore should be deprecated too", findKey.isDeprecated(), Matchers.is(true));
    }

    @Test
    public void testKeystoreWithProtectedKey() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig =
                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword(keyPassword).build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        AliasedKey findKey = localKeyStore.getAliasedKey(keyAlias);

        assertThat("Correct loaded keystore should contain key that was stores in it", findKey,
                Matchers.notNullValue());
    }

    @Test
    public void testKeyDeprecatedImplicitly() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig = new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword(keyPassword)
                .setDeprecated(true).build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        AliasedKey findKey = localKeyStore.getAliasedKey(keyAlias);

        assertThat("Correct loaded keystore should contain key that was stores in it", findKey,
                Matchers.notNullValue());

        assertThat("Key should be deprecated because if was set implicitly", findKey.isDeprecated(), Matchers.is(true));
    }

    @Test(expected = BadKeyPasswordException.class)
    public void testThrowReadableExceptionIfKeyProtectedButHasWrongPassword() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig =
                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword("BadPassword").build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        AliasedKey findKey = localKeyStore.getAliasedKey(keyAlias);


        fail("Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                + "we can get runtime exception that will be difficult detect");

    }

    @Test(expected = BadKeyPasswordException.class)
    public void testThrowReadableExceptionIfKeyProtectedButPasswordNotSpecified() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig = new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword("").build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        AliasedKey findKey = localKeyStore.getAliasedKey(keyAlias);


        fail("Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                + "we can get runtime exception that will be difficult detect");

    }

    @Test(expected = BadKeyPasswordException.class)
    public void testThrowReadableExceptionIfKeyNotProtectedButHasPassword() throws Exception {
        final String location = tmp.newFile("test.ks").getAbsolutePath();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(new File(location))) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig =
                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword("any").build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        AliasedKey findKey = localKeyStore.getAliasedKey(keyAlias);


        fail("Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                + "we can get runtime exception that will be difficult detect");

    }
}

