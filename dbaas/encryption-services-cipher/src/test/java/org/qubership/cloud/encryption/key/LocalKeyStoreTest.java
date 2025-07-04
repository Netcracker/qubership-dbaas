package org.qubership.cloud.encryption.key;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.encryption.cipher.exception.BadKeyPasswordException;
import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.ConfigurationBuildersFactory;
import org.qubership.cloud.encryption.config.xml.DefaultConfigurationCryptoProvider;
import org.qubership.cloud.encryption.config.xml.XmlConfigurationSerializer;
import org.qubership.cloud.encryption.key.exception.IllegalKeystoreConfigurationException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SuppressWarnings("unused")
class LocalKeyStoreTest {
    private ConfigurationParser parser;

    private static final String keystoreType = "JCEKS";
    private static final String ksPass = "someKsPassword";
    private static final String keyAlias = "mySecretKey";
    private static final String keyPassword = "myKeyPassword";
    private static final String algorithm = "AES";

    @TempDir
    private Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();
        parser = new XmlConfigurationSerializer(new DefaultConfigurationCryptoProvider(secretKey));
    }

    @Test
    void testNullConfigurationNotAvailable() {
        assertThrows(
                NullPointerException.class,
                () -> new LocalKeyStore(null),
                "it restrict contract"
        );
    }

    @Test
    void testKeyAvailableFromKeystore() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(location)) {
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
    void testNullAsResultFindKeyByAliasWhenTheyAbsetnInKeystore() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        try (FileOutputStream out = new FileOutputStream(location)) {
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
    void testFindKeyByAliasAndInterface() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(location)) {
            keyStore.store(out, ksPass.toCharArray());
        }

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        PrivateKey findKey = localKeyStore.getKeyByAlias(keyAlias, PrivateKey.class);

        assertThat("If find in keystore key have different type on requested we should return null like result",
                findKey, Matchers.nullValue());
    }

    @Test
    void testThrowReadableExceptionIfConfigurationIllegal() {
        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("MyBadKs")
                .setLocation("/u02/ks.ks").setPassword("123").setKeystoreType("NotExistsTypes").build();

        assertThrows(
                IllegalKeystoreConfigurationException.class,
                () -> new LocalKeyStore(config),
                "Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                        + "we can get runtime exception that will be difficult detect"
        );
    }

    @Test
    void testDeprecatedKeyStore() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(location)) {
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
    void testKeystoreWithProtectedKey() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(location)) {
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
    void testKeyDeprecatedImplicitly() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(location)) {
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

    @Test
    void testThrowReadableExceptionIfKeyProtectedButHasWrongPassword() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(location)) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig =
                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword("BadPassword").build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        assertThrows(
                BadKeyPasswordException.class,
                () -> localKeyStore.getAliasedKey(keyAlias),
                "Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                        + "we can get runtime exception that will be difficult detect"
        );
    }

    @Test
    void testThrowReadableExceptionIfKeyProtectedButPasswordNotSpecified() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(keyPassword.toCharArray()));

        try (FileOutputStream out = new FileOutputStream(location)) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig = new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword("").build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        assertThrows(
                BadKeyPasswordException.class,
                () -> localKeyStore.getAliasedKey(keyAlias),
                "Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                        + "we can get runtime exception that will be difficult detect"
        );

    }

    @Test
    void testThrowReadableExceptionIfKeyNotProtectedButHasPassword() throws Exception {
        final String location = tmp.resolve("test.ks").toAbsolutePath().toString();

        KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance(algorithm).generateKey();

        keyStore.setEntry(keyAlias, new KeyStore.SecretKeyEntry(secretKey),
                new KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(location)) {
            keyStore.store(out, ksPass.toCharArray());
        }

        KeyConfig keyConfig =
                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyAlias).setPassword("any").build();

        List<KeyConfig> keyConfigs = Collections.singletonList(keyConfig);

        LocalKeystoreConfig config = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("myks")
                .setLocation(location).setPassword(ksPass).setKeystoreType(keystoreType).setKeys(keyConfigs).build();

        LocalKeyStore localKeyStore = new LocalKeyStore(config);

        assertThrows(
                BadKeyPasswordException.class,
                () -> localKeyStore.getAliasedKey(keyAlias),
                "Illegal configuration should lead to correspond exception, if we pars configuration in asynchron "
                        + "we can get runtime exception that will be difficult detect"
        );
    }
}

