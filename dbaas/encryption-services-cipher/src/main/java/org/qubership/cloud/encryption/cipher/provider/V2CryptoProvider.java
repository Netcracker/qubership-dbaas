package org.qubership.cloud.encryption.cipher.provider;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.qubership.cloud.encryption.cipher.*;
import org.qubership.cloud.encryption.cipher.build.DecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.result.AbstractEncryptResult;
import org.qubership.cloud.encryption.config.crypto.CryptoSubsystemConfig;
import org.qubership.cloud.encryption.key.AliasedKey;
import org.qubership.cloud.encryption.key.KeyStore;
import org.qubership.cloud.encryption.key.KeyStoreRepository;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two version version algorithm that process encryption/decryption text. It provider inject information about use
 * algorithm and key alias that was use for encryption, it necessary for light-way migrate data when default key was
 * modify.<br/>
 * Template format:
 * 
 * <pre>
 * {v2c}{algorithm}{keyAlias}{base64(salt)}{base64(cryptData)}
 * </pre>
 * 
 * Example:
 * 
 * <pre>
 * {v2c}{DES}{secKeyAlias}{95ec+8MrCLM=}
 * </pre>
 * 
 * <pre>
 * {v2c}{AES/ECB/PKCS5Padding}{AESDefaultKey}{tE6Y9niEE3pMT6i5IHp5urF8Gm68PZLiZbrxONDptAGz5tWLtp5rnrrW1762exa0N+7IUMC075P8P9n7m9DCpKebsfKhUyNFcETbDMNTWXw=}
 * </pre>
 */
public class V2CryptoProvider extends AbstractJCACryptoProvider implements CryptoProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(V2CryptoProvider.class);

    private static final Pattern TEMPLATE_REGEXP = Pattern.compile(
            "^\\{v2c\\}(\\{(?<algorithm>[^\\{\\}]*)\\}){1}(\\{(?<keyAlias>[^\\{\\}]*)\\}){1}(\\{(?<iv>[a-zA-Z0-9\\=\\/\\+]+)\\}){0,1}(\\{(?<cryptData>[a-zA-Z0-9\\=\\/\\+]+)\\}){1}$");
    private static final Set<CryptoParameter> SUPPORT_PARAMETERS =
            Sets.immutableEnumSet(CryptoParameter.ALGORITHM, CryptoParameter.KEY, CryptoParameter.PROVIDER,
                    CryptoParameter.KEY_ALIS, CryptoParameter.INITIALIZED_VECTOR);

    @Nonnull
    private final CryptoSubsystemConfig config;

    public V2CryptoProvider(@Nonnull final KeyStore keyStore, @Nonnull final CryptoSubsystemConfig config) {
        this(keyStore, null, config);
    }

    public V2CryptoProvider(@Nonnull final KeyStore keyStore, final KeyStoreRepository keyStoreRepository,
            @Nonnull final CryptoSubsystemConfig config) {
        super(keyStore, keyStoreRepository);
        this.config = Preconditions.checkNotNull(config, "CryptoSubsystemConfig can't be null");
        if (!config.getDefaultAlgorithm().isPresent() || !config.getDefaultKeyAlias().isPresent()) {
            LOGGER.warn("Configuration {} define not all default parameter for encryption/decryption, "
                    + "it can lead to runtime exceptions when it parameter will not specify explicit "
                    + "in encryption/decryption request", config);
        }
    }

    @Nonnull
    @Override
    public EncryptResult encrypt(@Nonnull final EncryptionRequest request) {
        final EncryptedData result = jcaEncrypt(request);
        return new ProviderTemplateEncryptedResult(result);
    }


    @Nonnull
    @Override
    public DecryptResult decrypt(@Nonnull DecryptionRequest request) {
        byte[] decryptedMessage = jcaDecrypt(request);
        return new ImmutableDecryptResult(decryptedMessage, Charset.forName("UTF-8"));
    }

    @Override
    public boolean isKnowEncryptedFormat(@Nonnull String encryptedByTemplateText) {
        return TEMPLATE_REGEXP.matcher(encryptedByTemplateText).find();
    }

    @Override
    public DecryptResult decrypt(@Nonnull String encryptedByTemplateText) {
        Matcher matcher = TEMPLATE_REGEXP.matcher(encryptedByTemplateText);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Encrypted text '" + encryptedByTemplateText + "' doesn't have provider template");
        } else {
            final String algorithm = matcher.group("algorithm");
            final String keyAlias = matcher.group("keyAlias");
            final String base64Iv = matcher.group("iv");
            final byte[] iv = Base64.decodeBase64(base64Iv);
            String base64EncryptedData = matcher.group("cryptData");
            final byte[] cryptedByteArray = Base64.decodeBase64(base64EncryptedData);

            DecryptionRequest request = DecryptionRequestBuilder.createBuilder().setAlgorithm(algorithm)
                    .setKeyAlias(keyAlias).setEncryptedText(cryptedByteArray).setIV(iv).build();

            return decrypt(request);
        }
    }

    @Nonnull
    @Override
    public Set<CryptoParameter> getSupportsCryptoParameters() {
        return SUPPORT_PARAMETERS;
    }

    @Nonnull
    @Override
    protected String getDefaultKeyAlias() {
        if (!config.getDefaultKeyAlias().isPresent()) {
            throw new IllegalStateException("Default parameter 'defaultKeyAlias' not define in configuration: " + config
                    + " specify it parameter explicitly");
        }

        return config.getDefaultKeyAlias().get();
    }

    @Nonnull
    @Override
    protected String getDefaultAlgorithm() {
        if (!config.getDefaultAlgorithm().isPresent()) {
            throw new IllegalStateException("Default parameter 'defaultAlgorithm' not define in configuration: "
                    + config + " specify it parameter explicitly");
        }

        return config.getDefaultAlgorithm().get();
    }

    @Nullable
    @Override
    public EncryptionMetaInfo getEncryptedMetaInfo(@Nonnull String encryptedByTemplateText) {
        Matcher matcher = TEMPLATE_REGEXP.matcher(encryptedByTemplateText);
        if (!matcher.find()) {
            return null;
        } else {
            final String algorithm = matcher.group("algorithm");
            final String keyAlias = matcher.group("keyAlias");
            final AliasedKey key = getKeyFromKeyStoreInternal(keyAlias);
            final String base64Iv = matcher.group("iv");
            final byte[] iv = Base64.decodeBase64(base64Iv);
            String base64EncryptedData = matcher.group("cryptData");
            final byte[] cryptedByteArray = Base64.decodeBase64(base64EncryptedData);

            return new ImmutableEncryptionMetaInfo(cryptedByteArray, algorithm, key, iv);
        }
    }

    public class ProviderTemplateEncryptedResult extends AbstractEncryptResult {
        public ProviderTemplateEncryptedResult(@Nonnull EncryptedData cryptoResult) {
            super(cryptoResult);
        }

        @Nonnull
        @Override
        public String getResultAsEncryptionServiceTemplate() {
            if (!getEncryptedData().getUsedKey().getAlias().isPresent()) {
                throw new UnsupportedOperationException(
                        "Not available convert encryption parameters to template string safety: " + getEncryptedData());
            }

            String usedAlgorithm = getEncryptedData().getUsedAlgorithm();
            String keyAlias = getEncryptedData().getUsedKey().getAlias().get();


            StringBuilder builder = new StringBuilder("{v2c}");
            builder.append('{').append(usedAlgorithm).append('}');
            builder.append('{').append(keyAlias).append('}');
            if (getEncryptedData().getIV().isPresent()) {
                String iv = Base64.encodeBase64String(getEncryptedData().getIV().or(new byte[0]));
                builder.append('{').append(iv).append('}');
            }
            builder.append('{').append(getResultAsBase64String()).append('}');

            return builder.toString();
        }
    }
}

