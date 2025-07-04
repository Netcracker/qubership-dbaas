package org.qubership.cloud.encryption.key;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.config.xml.ConfigurationBuildersFactory;
import org.qubership.cloud.encryption.config.xml.DefaultConfigurationCryptoProvider;
import org.qubership.cloud.encryption.config.xml.XmlConfigurationSerializer;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.RemoteKeystoreXmlConf;
import org.qubership.cloud.encryption.key.exception.IllegalKeystoreConfigurationException;

import javax.annotation.Nonnull;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings({"unchecked", "unused"})
public class KeyStoreRepositoryImplTest {
    private ConfigurationParser parser;

    @TempDir
    private Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();
        parser = new XmlConfigurationSerializer(new DefaultConfigurationCryptoProvider(secretKey));
    }

    @Test
    void testEmptyConfigurationParseCorrectly() {
        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder().build();

        KeyStoreRepository result = new KeyStoreRepositoryImpl(config);
        assertThat("Configuration without keystores it's OK configuration", result, Matchers.notNullValue());
    }

    @Test
    void testNullLikeConfigurationCanNotBeSpecify() {
        assertThrows(
                NullPointerException.class,
                () -> new KeyStoreRepositoryImpl(null),
                "It restrict contract"
        );
    }

    @Test
    void testDefaultKeyStoreNotExistsInEmptyConfigureKeystoreRepository() {
        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder().build();

        KeyStoreRepository result = new KeyStoreRepositoryImpl(config);

        assertThat("When keystores not configure, repostiory always should return null", result.getDefaultKeystore(),
                Matchers.nullValue());
    }

    @Test
    void testDefaultKeystoreDefinesCorrectInCaseWhenManyKeystoresConfigure() throws Exception {
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
    void testGetKeyStoreByTheyIdentity() throws Exception {
        final String defaultKSIdentity = "ks-def";

        final LocalKeystoreConfig firstConfig = generateLocalKeystoreConfig(defaultKSIdentity);
        final LocalKeystoreConfig secondConfig = generateLocalKeystoreConfig("ks-2");
        final LocalKeystoreConfig threeConfig = generateLocalKeystoreConfig("ks-3");

        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(Arrays.asList(threeConfig, firstConfig, secondConfig)).setDefaultKeyStore(firstConfig)
                .build();


        KeyStoreRepository repository = new KeyStoreRepositoryImpl(config);

        KeyStore findKeystore = repository.getKeyStoreByIdentity("ks-2");

        Assertions.assertNotNull(findKeystore);
        assertThat(
                "In configuration was describe 3 keystores and each have unique name, so, we should can lockup it by it unique name",
                findKeystore.getIdentity(), equalTo("ks-2"));
    }

    @Test
    void testParseRemoveKeyStore() {
        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(List.of(new RemoteKeystoreXmlConf())).build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> new KeyStoreRepositoryImpl(config),
                "Remote keystore not implemented yet"
        );
    }

    @Test
    void testParseNotCorrectFilledKeystoreLeadToFailAllKeystores() {
        LocalKeystoreConfig notValidConfig = new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder("ks2")
                .setPassword("123").setKeystoreType("notExistsType").setLocation("/dev/null").build();

        KeystoreSubsystemConfig config = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                .setKeyStores(List.of(notValidConfig)).build();

        assertThrows(
                IllegalKeystoreConfigurationException.class,
                () -> new KeyStoreRepositoryImpl(config),
                "When one ofe keystore have not correct parameters should fail configure all another keystores, "
                        + "because if we ignore it exception, it lead to proble in runtime that will be detected after a while"
        );
    }

    private LocalKeystoreConfig generateLocalKeystoreConfig(@Nonnull String identity) throws Exception {
        final String keystoreType = "JCEKS";
        final String ksPass = "someKsPassword";
        final String location = tmp.resolve("test" + System.nanoTime() + ".ks").toAbsolutePath().toString();

        final String keyAlias = "mySecretKey";

        java.security.KeyStore keyStore = java.security.KeyStore.getInstance(keystoreType);
        keyStore.load(null, new char[0]);

        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();

        keyStore.setEntry(keyAlias, new java.security.KeyStore.SecretKeyEntry(secretKey),
                new java.security.KeyStore.PasswordProtection(new char[0]));

        try (FileOutputStream out = new FileOutputStream(location)) {
            keyStore.store(out, ksPass.toCharArray());
        }

        return new ConfigurationBuildersFactory().getLocalKeystoreConfigBuilder(identity).setLocation(location)
                .setPassword(ksPass).setKeystoreType(keystoreType).build();
    }
}
