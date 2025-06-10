package org.qubership.cloud.encryption.cipher;

import com.google.common.collect.ImmutableList;
import org.qubership.cloud.encryption.cipher.build.DecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.build.EncryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.exception.CryptoException;
import org.qubership.cloud.encryption.cipher.exception.IllegalCryptoParametersException;
import org.qubership.cloud.encryption.cipher.exception.NotExistsSecurityKey;
import org.qubership.cloud.encryption.cipher.provider.CryptoProvider;
import org.qubership.cloud.encryption.cipher.provider.V2CryptoProvider;
import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.config.xml.ConfigurationBuildersFactory;
import org.qubership.cloud.encryption.key.KeyStoreStub;
import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class CryptoServiceImplTest {
    private CryptoService cryptoService;
    private KeyStoreStub keyStore;

    @Before
    public void setUp() throws Exception {
        // String defaultAlgorithm = "AES/ECB/PKCS5Padding";
        String defaultAlgorithm = "AES/CBC/PKCS5Padding";
        String defaultKeyAlias = "AESDefaultKey";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");

        keyStore = new KeyStoreStub(Collections.singletonMap(defaultKeyAlias, keyGenerator.generateKey()));

        CryptoSubsystemConfig config = new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder()
                .setDefaultAlgorithm(defaultAlgorithm).setDefaultKeyAlias(defaultKeyAlias).build();

        CryptoProvider defaultProvider = new V2CryptoProvider(keyStore, config);
        List<CryptoProvider> availableProvider = ImmutableList.of(defaultProvider);
        cryptoService = new CryptoServiceImpl(defaultProvider, availableProvider);
    }

    @SuppressWarnings("unused")
    @Test(expected = NullPointerException.class)
    public void testNotAvailableEncryptNullMessage() throws Exception {
        String message = null;
        String result = cryptoService.encrypt(message);
        fail("null value can't be encrypted by contract, if it necessary null can be convert to empty string");
    }

    @Test
    public void testPlainTextEncryptsWithDefaultParameters() throws Exception {
        String message = "Very secret information";
        String result = cryptoService.encrypt(message);

        assertThat(
                "CryptoService should process encryption for plain text and if encryption not available service should "
                        + "throw exception, they can't result encrypted text that equal plain text, "
                        + "it means that encryption was not apply",
                result, not(equalTo(message)));

    }

    @Test
    public void testTextCanBeEncryptedTwice() throws Exception {
        String message = "secret";
        String firstEncrypt = cryptoService.encrypt(message);
        String secondEncrypt = cryptoService.encrypt(firstEncrypt);

        assertThat("CryptoService should encrypt any text that was request for encrypt. "
                + "Service can't check rule like isEncrypted() because plain text can have form already encrypted "
                + "and if we not encrypt it, it text will be process like plain text, and can lead to problem when "
                + "will be perform decrypt for it encrypted result",

                secondEncrypt, allOf(not(equalTo(firstEncrypt)), not(equalTo(message))));
    }

    @SuppressWarnings("unused")
    @Test(expected = NullPointerException.class)
    public void testNotAvailableDecryptNullString() throws Exception {
        String encryptedText = null;
        String result = cryptoService.decrypt(encryptedText).getResultAsString();
        fail("By contract describe in interface, null value can't be decrypted because they not contain information");
    }

    @Test
    public void encryptedTextWithDefaultParametersCanBeDecryptBySameWay() throws Exception {
        String original = "Very secret information";
        String crypted = cryptoService.encrypt(original);
        String decrypted = cryptoService.decrypt(crypted).getResultAsString();

        assertThat("When secret message encrypts with default algorithm and key encrypted result should be also "
                + "was decrypt with same default parameters", decrypted, equalTo(original));
    }

    @Test
    public void testEncryptWithDifferentKeyReturnNotEqualResult() throws Exception {
        final String algorithm = "AES/ECB/PKCS5Padding";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        final SecretKey firstSecretKey = keyGenerator.generateKey();
        final SecretKey secondSecretKey = keyGenerator.generateKey();

        assertThat(firstSecretKey, not(equalTo(secondSecretKey)));

        final String plainText = "secret information for encrypt two different keys";

        final String firstResult = cryptoService.encryptDSLRequest().algorithm(algorithm).key(firstSecretKey)
                .encrypt(plainText).getResultAsBase64String();

        final String secondResult = cryptoService.encryptDSLRequest().algorithm(algorithm).key(secondSecretKey)
                .encrypt(plainText).getResultAsBase64String();

        assertThat(
                "When requested encryption with predefine key, they should be use, "
                        + "if encrypt same text with same algorithm but with different keys ",
                firstResult, not(equalTo(secondResult)));
    }

    @Test(expected = IllegalCryptoParametersException.class)
    public void testNotAvailableEncryptTextWithNotExistsAlgorithm() throws Exception {
        final String algorithm = "NotExistsAlgorithm/NoExistsModule/NotExistPadding";

        final String plainText = "secret message for encrypt by not exists algorithm";

        final String encryptedResult =
                cryptoService.encryptDSLRequest().algorithm(algorithm).encrypt(plainText).getResultAsBase64String();
        fail("When via dsl specified not exists algorithm for encryption, service should try find provider for it algorithm, "
                + "and if provider not exists throws exception that specified bad parameter. "
                + "If exception not throws it can means that algorithm not applies: " + encryptedResult);
    }

    @Test
    public void testTextEncryptedWithKeyCanBeAlsoDecryptWithItKey() throws Exception {
        final String algorithm = "AES/ECB/PKCS5Padding";
        final SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        final String plainText = "Secret plain text";

        final String cryptedText = cryptoService.encryptDSLRequest().algorithm(algorithm).key(key).encrypt(plainText)
                .getResultAsBase64String();
        final String decryptText = cryptoService.decryptDSLRequest().algorithm(algorithm).key(key).decrypt(cryptedText)
                .getResultAsString();

        assertThat("When we encrypt text with own key, we should also can decrypt it by own key", decryptText,
                equalTo(plainText));
    }

    @Test(expected = CryptoException.class)
    public void testTextEcnryptedByDefaultKeyCanNotBeDecryptedByCustomKey() throws Exception {
        final SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        final String plainText = "secret";

        final String encryptedText = cryptoService.encrypt(plainText);
        final String decryptedText =
                cryptoService.decryptDSLRequest().key(key).decrypt(encryptedText).getResultAsString();

        assertThat(
                "When for decription specified not correct key, we can't get same plain text that was encrypted by different key",
                decryptedText, not(equalTo(plainText)));
    }

    @Test
    public void testEncryptTextByDifferentKeyAliasLeadToDifferentEncryptedTextResult() throws Exception {
        final String algorithm = "AES/ECB/PKCS5Padding";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");

        final String keyAliasOne = "oneKey";
        keyStore.registerKeyByAlias(keyAliasOne, keyGenerator.generateKey());

        final String keyAliasTwo = "twoKey";
        keyStore.registerKeyByAlias(keyAliasTwo, keyGenerator.generateKey());

        final String plainText = "secret";

        final String encryptedByFirstKey = cryptoService.encryptDSLRequest().algorithm(algorithm).keyAlias(keyAliasOne)
                .encrypt(plainText).getResultAsBase64String();
        final String encryptedBySecondKey = cryptoService.encryptDSLRequest().algorithm(algorithm).keyAlias(keyAliasTwo)
                .encrypt(plainText).getResultAsBase64String();

        assertThat(
                "When same plain text encrypts by different keys result string can't be equal, "
                        + "and if it equal it can means that was apply same key and same algorithm for encryption",
                encryptedByFirstKey, not(equalTo(encryptedBySecondKey)));
    }

    @Test
    public void testPriorityByExplicitSpecifiedKey() throws Exception {
        final String algorithm = "AES/ECB/PKCS5Padding";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");

        final String keyInKeyStoreAlias = "keyStoreSecretKey";
        keyStore.registerKeyByAlias(keyInKeyStoreAlias, keyGenerator.generateKey());

        final SecretKey explicitKey = keyGenerator.generateKey();

        final String plaintext = "secret";

        final String encryptedWithPriorityOnExplicitKey = cryptoService.encryptDSLRequest().algorithm(algorithm)
                .keyAlias(keyInKeyStoreAlias).key(explicitKey).encrypt(plaintext).getResultAsBase64String();

        final String encryptedWithExplicitKey = cryptoService.encryptDSLRequest().algorithm(algorithm).key(explicitKey)
                .encrypt(plaintext).getResultAsBase64String();

        assertThat("In case when for encryption request specifies opposite parameters like keyAlias and explicit key, "
                + "priority should be safe on explicit parameter. "
                + "In current test we want that for two encryption will be use explicit key and result will be same",
                encryptedWithPriorityOnExplicitKey, equalTo(encryptedWithExplicitKey));
    }

    @Test
    public void testPriorityByExplicitSpecifiedKeyDuringDecrypt() throws Exception {
        final String algorithm = "AES/ECB/PKCS5Padding";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");

        final String keyInKeyStoreAlias = "keyStoreSecretKey";
        keyStore.registerKeyByAlias(keyInKeyStoreAlias, keyGenerator.generateKey());

        final SecretKey explicitKey = keyGenerator.generateKey();

        final String plaintext = "secret";
        final String encryptedByExplicitKey = cryptoService.encryptDSLRequest().key(explicitKey).algorithm(algorithm)
                .encrypt(plaintext).getResultAsBase64String();

        final String decryptedText = cryptoService.decryptDSLRequest().key(explicitKey).keyAlias(keyInKeyStoreAlias)
                .algorithm(algorithm).decrypt(encryptedByExplicitKey).getResultAsString();

        assertThat(
                "In case when for decryption request specifies opposite parameters like keyAlias and explicit key, "
                        + "priority should be safe on explicit parameter. "
                        + "In current test we want that for decryption request that contains explicit key and key "
                        + "alias will use explicit key for decryption text that was encrypt with explicit key",
                decryptedText, equalTo(plaintext));
    }

    @Test(expected = NotExistsSecurityKey.class)
    public void testNotAvailableEncryptTextByNotExistsInKeyStoreKeyAlias() throws Exception {
        final String notExistsKeyAlias = "notExistsInKeyStoreKeyAlias";

        final String plaintext = "secret";
        final String result = cryptoService.encryptDSLRequest().keyAlias(notExistsKeyAlias).encrypt(plaintext)
                .getResultAsBase64String();

        fail("When for encryption specified key alias that not exists in KeyStore encryption service should "
                + "fail with exception that specified key not exists, but if we ignore it message and encrypt text "
                + "with default key, they will be never decrypted on another mashine. In that test encryption result: "
                + result);
    }

    @Test(expected = NotExistsSecurityKey.class)
    public void testNotAvailableDecryptTextByNotExistsInKeyStoreKeyAlias() throws Exception {
        final String algorithm = "DES";
        final String keyAlias = "uniqueSecretKeyAlias";
        final String plainText = "secret";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("DES");
        keyGenerator.init(56);

        keyStore.registerKeyByAlias(keyAlias, keyGenerator.generateKey());

        final String encryptedText = cryptoService.encryptDSLRequest().algorithm(algorithm).keyAlias(keyAlias)
                .encrypt(plainText).getResultAsBase64String();

        // remove key from KeyStore
        keyStore.clear();

        final String decryptText = cryptoService.decryptDSLRequest().algorithm(algorithm).keyAlias(keyAlias)
                .decrypt(encryptedText).getResultAsString();

        fail("When by some reason specified key alias not exists in KeyStore we should fail with exception about not found key. "
                + "If we will be use default key for decrypt it can lead to not correct decryption result and as result difficult to debug bugs."
                + "Decrypted text result: " + decryptText + " Original encrypted text result: " + plainText);
    }

    @Test
    public void testEncryptedByTemplateCanBeDecryptWithoutNecessaryKnowAboutAlgorithmOrKeyName() throws Exception {
        final String algorithm = "DES";
        final String keyAlias = "secKeyAlias";
        final String plainText = "secret";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("DES");
        keyGenerator.init(56);

        keyStore.registerKeyByAlias(keyAlias, keyGenerator.generateKey());

        final String encryptedByTemplate = cryptoService.encryptDSLRequest().algorithm(algorithm).keyAlias(keyAlias)
                .encrypt(plainText).getResultAsEncryptionServiceTemplate();

        final String decryptedFromTemplateText = cryptoService.decrypt(encryptedByTemplate).getResultAsString();

        assertThat("When key encrypted with some parameters and it parameters inject result message "
                + "for example like template {v2}{algorithm}{keyAlias}{data} it not necessary specify it parameters during decrypt, "
                + "because all information already exist in decrypt data ",

                decryptedFromTemplateText, equalTo(plainText));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testNotAvailableCreateTemplateDecryptedStringWhenItCanRestrictSecureRules() throws Exception {
        final String algorithm = "DES";
        final String plainText = "secret";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("DES");
        keyGenerator.init(56);

        SecretKey secretKey = keyGenerator.generateKey();

        final EncryptResult cryptoResult =
                cryptoService.encryptDSLRequest().algorithm(algorithm).key(secretKey).encrypt(plainText);

        final String template = cryptoResult.getResultAsEncryptionServiceTemplate();

        fail("When plain text encrypts with some custom parameters for example own Secret Key that not stores in KeyStore, "
                + "we can't build template string that inject encryption parameters for easy decrypt, "
                + "because it we inject key it restrict security rules because secret key and crypted data by it key stores together."
                + "If we encrypt data without inject some parameters, it restrict encryption service contract because when we get "
                + "it string to decryptMethod we can't decrypt read required parameters for decryption. In that test we get result: "
                + template);
    }

    @Test
    public void testCryptedWithDefaultParametersCanBeDecryptedWithDefaultParametersWhenWeSetItLikeTemplate()
            throws Exception {
        // withoult salt algorithm
        String defaultAlgorithm = "AES";
        String defaultKeyAlias = "AESDefaultKey";

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");

        keyStore = new KeyStoreStub(Collections.singletonMap(defaultKeyAlias, keyGenerator.generateKey()));

        CryptoSubsystemConfig config = new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder()
                .setDefaultAlgorithm(defaultAlgorithm).setDefaultKeyAlias(defaultKeyAlias).build();

        CryptoProvider defaultProvider = new V2CryptoProvider(keyStore, config);
        List<CryptoProvider> availableProvider = ImmutableList.of(defaultProvider);
        cryptoService = new CryptoServiceImpl(defaultProvider, availableProvider);


        final String plainText = "secret";

        final String encryptedText = cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();

        // decrypt like from template
        final String decryptedText = cryptoService.decrypt(encryptedText).getResultAsString();

        assertThat(
                "Some providers can not have own template like have v2c provider. for example instead of well-know template provider can return simple base64(encryptedData) so, "
                        + "if parameters for encryption was default, with same default parameters we can decrypt it",
                decryptedText, equalTo(plainText));
    }

    @Test
    public void testSameEncryptedTextHaveDifferentEncryptedResult() throws Exception {
        String plainText = "Secure string for encrypt with salt";

        final String encryptedTextFirst =
                cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();
        final String encryptedTextSecond =
                cryptoService.encryptDSLRequest().encrypt(plainText).getResultAsBase64String();

        assertThat(
                "During encryption same value should be always add additoonal random IV, that allow additional encryption "
                        + "because if malefactor will now that they for example get salary 1500$ and data will store in database "
                        + "withoult salt they also can get all people that gets same amount salary",

                encryptedTextFirst, not(equalTo(encryptedTextSecond)));
    }

    @Test
    public void testSameEnctyptedTextWithSameIVHaveEqualEncryptedResult() throws Exception {
        String plainText = "secret";
        byte[] iv = new byte[16];
        ThreadLocalRandom.current().nextBytes(iv);

        final String encryptedTextFirst =
                cryptoService.encryptDSLRequest().initializedVector(iv).encrypt(plainText).getResultAsBase64String();
        final String encryptedTextSecond =
                cryptoService.encryptDSLRequest().initializedVector(iv).encrypt(plainText).getResultAsBase64String();

        assertThat(
                "When encryption process with same initialized vector we should get same result, because initialized vector works like salt in hashing functions",
                encryptedTextFirst, equalTo(encryptedTextSecond));
    }

    @Test
    public void testEncryptedDataHaveCorrectIV() throws Exception {
        String plainText = "secret";
        byte[] iv = new byte[16];
        ThreadLocalRandom.current().nextBytes(iv);

        byte[] usedIv = cryptoService.encryptDSLRequest().initializedVector(iv).encrypt(plainText).getEncryptedData()
                .getIV().get();

        assertThat(
                "When initialized vector specified explicitly for encryption, "
                        + "in encryption result we should can read it initialized vector as is",
                Base64.encodeBase64String(iv), equalTo(Base64.encodeBase64String(usedIv)));
    }

    @Test
    public void testProviderCanEncryptDataWithoutDefaultParameters() throws Exception {
        CryptoSubsystemConfig config = new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder().build();

        CryptoProvider provider = new V2CryptoProvider(keyStore, config);

        SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();
        final String plainText = "secret";
        final String algorithm = "AES";

        byte[] crypted = provider.encrypt(EncryptionRequestBuilder.createBuilder().setAlgorithm(algorithm)
                .setKey(secretKey).setPlainText(plainText).build()).getResultAsByteArray();

        String decrypted = provider.decrypt(DecryptionRequestBuilder.createBuilder().setAlgorithm(algorithm)
                .setKey(secretKey).setEncryptedText(crypted).build()).getResultAsString();


        assertThat(
                "When not all default parameters define in configuration, crypto provider can work limited mode - throw exception when necessary use default parameter, "
                        + "but if in request specified all required parameter - algorithm, key, etc "
                        + "encryption/decryption function should work well",
                decrypted, equalTo(plainText));
    }

    @Test(expected = IllegalStateException.class)
    public void testIllegalStateExceptionWhenNecessaryUseDefaultParameter_algorithm_AndTheyNotDefined()
            throws Exception {
        CryptoSubsystemConfig config = new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder()
                .setDefaultKeyAlias("secretKeyAlias").build();

        SecretKey secretKey = KeyGenerator.getInstance("DES").generateKey();

        keyStore.registerKeyByAlias(config.getDefaultKeyAlias().get(), secretKey);

        V2CryptoProvider provider = new V2CryptoProvider(keyStore, config);

        final String plainText = "secret";

        byte[] crypted = provider.encrypt(EncryptionRequestBuilder.createBuilder().setPlainText(plainText).build())
                .getResultAsByteArray();

        fail("When in encryption or decryption request not defined some default parameter and they should be use provider should "
                + "throw correspond exception, because if we will be use system default parameters then can conflict between themselves, "
                + "for example in configuration specify like default key that was generate for DES algorithm but default algorithm "
                + "was not specify and if provider like default algorithm will be use hardcoded AES algorithm "
                + "it can lead to encryption/decryption exception that size block not correct for algorithm: "
                + Arrays.toString(crypted));
    }

    @Test(expected = IllegalStateException.class)
    public void testIllegalStateExceptionWhenNecessaryUseDefaultParameter_defaultKeyAlias_AndTheyNotDefined()
            throws Exception {
        CryptoSubsystemConfig config =
                new ConfigurationBuildersFactory().getCryptoSubsystemConfigBuilder().setDefaultAlgorithm("DES").build();

        V2CryptoProvider provider = new V2CryptoProvider(keyStore, config);

        final String plainText = "secret";

        byte[] crypted = provider.encrypt(EncryptionRequestBuilder.createBuilder().setPlainText(plainText).build())
                .getResultAsByteArray();

        fail("When in encryption or decryption request not defined some default parameter and they should be use provider should "
                + "throw correspond exception, because if we will be use system default parameters then can conflict between themselves, "
                + "for example in configuration specify like default key that was generate for DES algorithm but default algorithm "
                + "was not specify and if provider like default algorithm will be use hardcoded AES algorithm "
                + "it can lead to encryption/decryption exception that size block not correct for algorithm: "
                + Arrays.toString(crypted));
    }

    @Test
    public void testEncryptByAsymmetricKeyBigText() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        final int keySize = 1024 * 2;
        final String algorithm = "RSA/NONE/NoPadding";

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BC");
        generator.initialize(keySize);

        final KeyPair keyPair = generator.generateKeyPair();

        final StringBuilder secretBuilder = new StringBuilder();
        while (secretBuilder.length() < 128) {
            secretBuilder.append("secret").append("(").append(secretBuilder.length()).append(")");
        }
        final String plainText = secretBuilder.toString();

        System.out.println("PlainText: " + plainText);

        final String crypted =
                cryptoService.encryptDSLRequest().algorithm(algorithm).provider(BouncyCastleProvider.PROVIDER_NAME)
                        .key(keyPair.getPublic()).encrypt(plainText).getResultAsBase64String();

        System.out.println("Encrypted big text: " + crypted);

        final String decrypted =
                cryptoService.decryptDSLRequest().algorithm(algorithm).provider(BouncyCastleProvider.PROVIDER_NAME)
                        .key(keyPair.getPrivate()).decrypt(crypted).getResultAsString();

        assertThat(
                "When text that we try encrypt more them length key text should be splitted by part, in that test "
                        + "we want that encrypted by public key big secret will be correctly decrypted by private key",
                decrypted, equalTo(plainText));
    }

    @Test
    public void testEncryptBySymmetricKeyBigText() throws Exception {
        final StringBuilder secretBuilder = new StringBuilder();
        while (secretBuilder.length() < 10240) {
            secretBuilder.append("secret").append("(").append(secretBuilder.length()).append(")");
        }
        final String plainText = secretBuilder.toString();

        final String crypted = cryptoService.encrypt(plainText);

        System.out.println("Encrypted big text: " + crypted);

        final String decrypted = cryptoService.decrypt(crypted).getResultAsString();

        assertThat(decrypted, equalTo(decrypted));
    }

    @Test
    public void testGetEncryptedMetaInfo() {
        String defaultAlgorithm = "AES/CBC/PKCS5Padding";
        String defaultKeyAlias = "AESDefaultKey";
        String message = "Very secret information";
        String result = cryptoService.encrypt(message);
        EncryptionMetaInfo metaInfo = cryptoService.getEncryptedMetaInfo(result);

        assertThat(metaInfo, notNullValue());
        assertThat(metaInfo.getAlgorithm(), equalTo(defaultAlgorithm));
        assertThat(metaInfo.getKey(), notNullValue());
        assertThat(metaInfo.getKey().getAlias().get(), equalTo(defaultKeyAlias));
    }

    @Test
    public void testGetEncryptedMetaInfoBadEncryptedData() {
        String result = "So bad result";
        EncryptionMetaInfo metaInfo = cryptoService.getEncryptedMetaInfo(result);

        assertThat(metaInfo, nullValue());
    }
}

