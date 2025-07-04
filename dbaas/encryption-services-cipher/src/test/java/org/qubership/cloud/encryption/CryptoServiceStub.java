package org.qubership.cloud.encryption;

import com.google.common.base.Throwables;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import org.qubership.cloud.encryption.cipher.*;
import org.qubership.cloud.encryption.cipher.dsl.decrypt.ChainedDecryptionRequest;
import org.qubership.cloud.encryption.cipher.dsl.decrypt.ChainedDecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.dsl.encrypt.ChainedEncryptionRequest;
import org.qubership.cloud.encryption.cipher.dsl.encrypt.ChainedEncryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.exception.DecryptException;
import org.qubership.cloud.encryption.cipher.exception.EncryptException;
import org.qubership.cloud.encryption.cipher.exception.NotExistsSecurityKey;
import org.qubership.cloud.encryption.cipher.provider.EncryptedData;
import org.qubership.cloud.encryption.cipher.provider.EncryptedDataBuilder;
import org.qubership.cloud.encryption.cipher.result.NotTemplateEncryptResult;
import org.qubership.cloud.encryption.key.AliasedKey;
import org.qubership.cloud.encryption.key.ImmutableAliasedKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.nio.charset.Charset;
import java.security.*;
import java.util.Arrays;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

public class CryptoServiceStub implements CryptoService {
    public static final String DEFAULT_KEY_ALIAS = "defaultKeyAlias";
    private final BiMap<byte[], EncryptResult> predefinePlainToEncrypt = HashBiMap.create();
    private final Map<String, AliasedKey> keystore = Maps.newHashMap();

    public CryptoServiceStub() {
        SecretKey secretKey = null;
        try {
            secretKey = KeyGenerator.getInstance("AES").generateKey();
            keystore.put(DEFAULT_KEY_ALIAS, new ImmutableAliasedKey(secretKey, DEFAULT_KEY_ALIAS));
        } catch (NoSuchAlgorithmException e) {
            Throwables.propagate(e);
        }
    }

    public void addPredefineEncrypt(byte[] plainText, EncryptResult encryptResult) {
        predefinePlainToEncrypt.put(plainText, encryptResult);
    }

    public void addPredefineEncrypt(Map<byte[], EncryptResult> encrypts) {
        predefinePlainToEncrypt.putAll(encrypts);
    }

    public void addKeyToKeystore(String keyAlias, Key key) {
        AliasedKey aliasedKey = new ImmutableAliasedKey(key, keyAlias);
        keystore.put(keyAlias, aliasedKey);
    }


    @Nonnull
    @Override
    public String encrypt(@Nonnull String data) {
        checkNotNull(data, "Data for encrypt can't be null");
        return encryptDSLRequest().encrypt(data).getResultAsEncryptionServiceTemplate();
    }

    @Nonnull
    @Override
    public String encrypt(@Nonnull byte[] data) {
        checkNotNull(data, "Data byte array can't be null");
        return encryptDSLRequest().encrypt(data).getResultAsEncryptionServiceTemplate();
    }

    @Nonnull
    @Override
    public EncryptResult encrypt(@Nonnull EncryptionRequest request) {
        byte[] plainText = request.getPlainText();
        if (predefinePlainToEncrypt.containsKey(plainText)) {
            // todo what predefine for particular algorithm and key?
            return predefinePlainToEncrypt.get(plainText);
        }

        try {
            String algorithm = getAlgorithm(request);
            final AliasedKey key = getKeyFromRequest(request);

            Cipher cipher = initializeCipher(Cipher.ENCRYPT_MODE, request);

            byte[] cryptedData = cipher.doFinal(request.getPlainText());

            EncryptedData encryptedData = new EncryptedDataBuilder().setUsedAlgorithm(algorithm).setUsedKey(key)
                    .setInitializedVector(cipher.getIV()).setEncryptedText(cryptedData).build();

            return new NotTemplateEncryptResult(encryptedData);
        } catch (GeneralSecurityException e) {
            throw new EncryptException("Exception occurs during encryption plaint ext from request:" + request, e);
        }
    }

    private Cipher initializeCipher(int mode, @Nonnull CryptoRequest request)
            throws InvalidKeyException, InvalidAlgorithmParameterException, NoSuchPaddingException,
            NoSuchAlgorithmException, NoSuchProviderException {
        Cipher cipher = getCipher(request);
        AliasedKey key = getKeyFromRequest(request);

        if (request.getIV().isPresent()) {
            cipher.init(mode, key.getKey(), new IvParameterSpec(request.getIV().get()));
        } else {
            cipher.init(mode, key.getKey());
        }

        return cipher;
    }

    private Cipher getCipher(@Nonnull CryptoRequest request)
            throws NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException {
        String algorithm = getAlgorithm(request);
        Cipher cipher;
        if (request.getProvider().isPresent()) {
            cipher = Cipher.getInstance(algorithm, request.getProvider().get());
        } else {
            cipher = Cipher.getInstance(algorithm);
        }
        return cipher;
    }

    private String getAlgorithm(@Nonnull CryptoRequest request) {
        return request.getAlgorithm().or("AES");
    }


    @Nonnull
    protected AliasedKey getKeyFromRequest(@Nonnull final CryptoRequest request) {
        if (request.getKey().isPresent()) {
            return new ImmutableAliasedKey(request.getKey().get());
        } else {
            final String alias;
            if (request.getKeyAlias().isPresent()) {
                alias = request.getKeyAlias().get();
            } else {
                alias = DEFAULT_KEY_ALIAS;
            }

            return getKeyFromKeyStore(alias);
        }
    }

    @Nonnull
    public AliasedKey getKeyFromKeyStore(@Nonnull String alias) {
        AliasedKey key = keystore.get(alias);
        if (key == null) {
            throw new NotExistsSecurityKey(String.format("Key can't be find by alias '%s'", alias));
        }

        return key;
    }

    @Nonnull
    @Override
    public DecryptResult decrypt(@Nonnull String data) {
        checkNotNull(data, "Data for decrypt can't be null");
        return decryptDSLRequest().decrypt(data);
    }

    @Nonnull
    @Override
    public DecryptResult decrypt(@Nonnull DecryptionRequest request) {
        for (Map.Entry<byte[], EncryptResult> entry : predefinePlainToEncrypt.entrySet()) {
            byte[] encryptedText = request.getEncryptedText();
            EncryptResult encryptResult = entry.getValue();
            if (Arrays.equals(encryptedText, encryptResult.getResultAsByteArray())) {
                return new ImmutableDecryptResult(entry.getKey(), Charset.forName("UTF-8"));
            }
        }

        try {
            Cipher cipher = initializeCipher(Cipher.DECRYPT_MODE, request);

            byte[] decryptedData = cipher.doFinal(request.getEncryptedText());

            return new ImmutableDecryptResult(decryptedData, Charset.forName("UTF-8"));
        } catch (GeneralSecurityException e) {
            throw new DecryptException("Exception occurs during decrypt data from request:" + request, e);
        }
    }


    @Nonnull
    @Override
    public ChainedEncryptionRequest encryptDSLRequest() {
        return new ChainedEncryptionRequestBuilder(this);
    }

    @Nonnull
    @Override
    public ChainedDecryptionRequest decryptDSLRequest() {
        return new ChainedDecryptionRequestBuilder(this);
    }

    @Nullable
    @Override
    public EncryptionMetaInfo getEncryptedMetaInfo(@Nonnull String encryptedData) {
        return null;
    }
}

