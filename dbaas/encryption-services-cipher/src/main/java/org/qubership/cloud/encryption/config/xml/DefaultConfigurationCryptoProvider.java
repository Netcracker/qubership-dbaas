package org.qubership.cloud.encryption.config.xml;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.qubership.cloud.encryption.cipher.exception.CryptoException;
import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.EncryptionConfiguration;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.apache.commons.codec.binary.Base64;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultConfigurationCryptoProvider implements ConfigurationCryptoProvider {
    private static final String CURRENT_CRYPTO_VERSION = "v1";

    private static final Map<String, String> VERSION_TO_ALGORITHM = ImmutableMap.of("v1", "AES/CBC/PKCS5Padding");

    private static final String DEFAULT_ALGORITHM = VERSION_TO_ALGORITHM.get(CURRENT_CRYPTO_VERSION);

    private static final Pattern TEMPLATE_REGEXP = Pattern.compile(
            "^\\{(?<version>v[0-9]*)\\}\\{(?<salt>[a-zA-Z0-9\\=\\/\\+]+)\\}\\{(?<cryptData>[a-zA-Z0-9\\=\\/\\+]+)\\}$");

    private class BooleanContainer implements Consumer<Boolean>, BooleanSupplier {
        private boolean value = false;

        @Override
        public boolean getAsBoolean() {
            return value;
        }

        @Override
        public void accept(@Nonnull Boolean value) {
            this.value |= value;
        }
    }

    @FunctionalInterface
    private static interface CreateKeystoreConfigFunction {
        LocalKeystoreConfig create(@Nonnull LocalKeystoreConfig keystoreConfig, @Nonnull String keyPassword);
    }

    @FunctionalInterface
    private static interface CreateKeyConfigFunction {
        KeyConfig create(@Nonnull KeyConfig keyConfig, @Nonnull String keyPassword);
    }

    /**
     * Default key for encryption and decryption configuration
     */
    @Nonnull
    private final Key key;

    public DefaultConfigurationCryptoProvider(@Nonnull Key secretKey) {
        key = Preconditions.checkNotNull(secretKey, "Key for decrypt/encrypt can't be null");
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.qubership.cloud.encryption.config.xml.ConfigurationCryptoProvider#cryptSecureParameters(org.qubership.
     * security.encryption.config.EncryptionConfiguration,
     * org.qubership.cloud.encryption.config.xml.XmlConfigurationParser)
     */
    @SuppressWarnings("unchecked")
    @Override
    public EncryptionConfiguration cryptSecureParameters(@Nonnull EncryptionConfiguration config,
            @Nonnull ConfigurationParser parser) {
        final KeystoreSubsystemConfig keystoreSubsystem = config.getKeyStoreSubsystemConfig();
        final List<KeystoreConfig> keystores = keystoreSubsystem.getKeyStores();
        final List<KeystoreConfig> processKeystore = Lists.newArrayList();
        final BooleanContainer updateNecessaryContainer = new BooleanContainer();

        keystores.forEach(
                kc -> processKeystore.add(updateConfig((LocalKeystoreConfig keystoreConfig, String password) -> {
                    if (!isEncrypted(password)) {
                        String cryptedPassword = crypt(password);
                        keystoreConfig = new ConfigurationBuildersFactory()
                                .getLocalKeystoreConfigBuilder(keystoreConfig.getKeystoreIdentifier())
                                .copyParameters(keystoreConfig).setPassword(cryptedPassword).build();
                        updateNecessaryContainer.accept(true);
                    }

                    return keystoreConfig;
                }, (KeyConfig keyConfig, String keyPassword) -> {
                    if (!isEncrypted(keyPassword)) {
                        String decryptedPassword = crypt(keyPassword);
                        KeyConfig localKeyConfig =
                                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyConfig.getAlias())
                                        .copyParameters(keyConfig).setPassword(decryptedPassword).build();
                        updateNecessaryContainer.accept(true);
                        keyConfig = localKeyConfig;
                    }

                    return keyConfig;
                }, updateNecessaryContainer, kc)));

        if (updateNecessaryContainer.getAsBoolean()) {
            KeystoreSubsystemConfig cryptedKeyStores = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                    .copyParameters(keystoreSubsystem).setKeyStores(processKeystore).build();

            return new ConfigurationBuildersFactory().getConfigurationBuilder().copyParameters(config)
                    .setKeystoreSubsystemConfig(cryptedKeyStores).build();
        }

        return config;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.qubership.cloud.encryption.config.xml.ConfigurationCryptoProvider#decryptSecureParameters(org.qubership.
     * security.encryption.config.EncryptionConfiguration,
     * org.qubership.cloud.encryption.config.xml.XmlConfigurationParser)
     */
    @SuppressWarnings("unchecked")
    @Override
    public DecryptionResult decryptSecureParameters(@Nonnull EncryptionConfiguration config,
            @Nullable ConfigurationParser parser) {
        final KeystoreSubsystemConfig keystoreSubsystem = config.getKeyStoreSubsystemConfig();
        final List<KeystoreConfig> keystores = keystoreSubsystem != null 
                ? keystoreSubsystem.getKeyStores()
                        : Collections.emptyList();
        final List<KeystoreConfig> processKeystore = Lists.newArrayList();

        final BooleanContainer updateNecessaryContainer = new BooleanContainer();
        final BooleanContainer decryptedSecureDataExistsContainer = new BooleanContainer();

        keystores.forEach(
                kc -> processKeystore.add(updateConfig((LocalKeystoreConfig keystoreConfig, String password) -> {
                    if (isEncrypted(password)) {
                        String decryptedPassword = decrypt(password);
                        keystoreConfig = new ConfigurationBuildersFactory()
                                .getLocalKeystoreConfigBuilder(keystoreConfig.getKeystoreIdentifier())
                                .copyParameters(keystoreConfig).setPassword(decryptedPassword).build();

                        updateNecessaryContainer.accept(true);
                    } else {
                        decryptedSecureDataExistsContainer.accept(true);
                    }

                    return keystoreConfig;
                }, (KeyConfig keyConfig, String keyPassword) -> {
                    if (isEncrypted(keyPassword)) {
                        String decryptedPassword = decrypt(keyPassword);
                        KeyConfig localKeyConfig =
                                new ConfigurationBuildersFactory().getKeyConfigBuilder(keyConfig.getAlias())
                                        .copyParameters(keyConfig).setPassword(decryptedPassword).build();
                        updateNecessaryContainer.accept(true);
                        keyConfig = localKeyConfig;
                    } else {
                        decryptedSecureDataExistsContainer.accept(true);
                    }

                    return keyConfig;
                }, updateNecessaryContainer, kc)));

        if (updateNecessaryContainer.getAsBoolean()) {
            KeystoreSubsystemConfig cryptedKeyStores = new ConfigurationBuildersFactory().getKeystoreConfigBuilder()
                    .copyParameters(keystoreSubsystem).setKeyStores(processKeystore).build();

            EncryptionConfiguration decryptedConfiguration =
                    new ConfigurationBuildersFactory().getConfigurationBuilder().copyParameters(config)
                            .setKeystoreSubsystemConfig(cryptedKeyStores).build();

            return new DecryptionResult(decryptedConfiguration, decryptedSecureDataExistsContainer.getAsBoolean());
        }

        return new DecryptionResult(config, decryptedSecureDataExistsContainer.getAsBoolean());
    }

    private KeystoreConfig updateConfig(@Nonnull CreateKeystoreConfigFunction createKeystoreConfigFunction,
            @Nonnull CreateKeyConfigFunction createKeyConfigFunction, @Nonnull BooleanSupplier updateNecessarySupplier,
            @Nonnull KeystoreConfig keystoreConfig) {

        if (keystoreConfig instanceof LocalKeystoreConfig) {
            LocalKeystoreConfig localKeystoreConfig = (LocalKeystoreConfig) keystoreConfig;
            final List<KeyConfig> keys = Lists.newArrayList();
            final List<KeyConfig> sourceKeys = localKeystoreConfig.getKeys();

            sourceKeys.forEach(keyConfig -> {
                String keyPassword = keyConfig.getPassword();
                if (keyPassword != null) {
                    keyConfig = createKeyConfigFunction.create(keyConfig, keyPassword);
                }

                keys.add(keyConfig);
            });

            if (updateNecessarySupplier.getAsBoolean()) {
                localKeystoreConfig = new ConfigurationBuildersFactory()
                        .getLocalKeystoreConfigBuilder(localKeystoreConfig.getKeystoreIdentifier())
                        .copyParameters(localKeystoreConfig).setKeys(keys).build();
            }

            String password = localKeystoreConfig.getPassword();
            if (password != null) {
                localKeystoreConfig = createKeystoreConfigFunction.create(localKeystoreConfig, password);
            }

            return localKeystoreConfig;
        }

        return keystoreConfig;
    }

    private String crypt(@Nonnull String str) {
        try {
            Cipher cipher = Cipher.getInstance(DEFAULT_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);

            byte[] encrypted = cipher.doFinal(str.getBytes(Charset.forName("UTF-8")));
            byte[] salt = cipher.getIV();

            String base64CryptedData = Base64.encodeBase64String(encrypted);
            String base64SaltData = Base64.encodeBase64String(salt);

            return String.format("{%s}{%s}{%s}", CURRENT_CRYPTO_VERSION, base64SaltData, base64CryptedData);
        } catch (IllegalBlockSizeException | BadPaddingException | InvalidKeyException | NoSuchAlgorithmException
                | NoSuchPaddingException e) {
            throw new CryptoException("Data can't be crypted for same configuration", e);
        }
    }

    private String decrypt(@Nonnull final String str) {
        Matcher matcher = TEMPLATE_REGEXP.matcher(str);
        if (matcher.find()) {
            String version = matcher.group("version");

            byte[] salt = Base64.decodeBase64(matcher.group("salt"));
            byte[] cryptedData = Base64.decodeBase64(matcher.group("cryptData"));

            try {
                Cipher cipher = Cipher.getInstance(VERSION_TO_ALGORITHM.get(version));
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(salt));

                byte[] decrypted = cipher.doFinal(cryptedData);

                return new String(decrypted, Charset.forName("UTF-8"));
            } catch (GeneralSecurityException e) {
                throw new CryptoException("Data can't be crypted for same configuration", e);
            }
        } else {
            return str;
        }
    }

    private boolean isEncrypted(@Nonnull String str) {
        return TEMPLATE_REGEXP.matcher(str).find();
    }
}

