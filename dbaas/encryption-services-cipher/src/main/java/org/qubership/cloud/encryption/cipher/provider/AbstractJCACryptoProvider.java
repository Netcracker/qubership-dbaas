package org.qubership.cloud.encryption.cipher.provider;

import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.cipher.CryptoRequest;
import org.qubership.cloud.encryption.cipher.DecryptionRequest;
import org.qubership.cloud.encryption.cipher.EncryptionRequest;
import org.qubership.cloud.encryption.cipher.exception.DecryptException;
import org.qubership.cloud.encryption.cipher.exception.EncryptException;
import org.qubership.cloud.encryption.cipher.exception.IllegalCryptoParametersException;
import org.qubership.cloud.encryption.cipher.exception.NotExistsSecurityKey;
import org.qubership.cloud.encryption.key.AliasedKey;
import org.qubership.cloud.encryption.key.ImmutableAliasedKey;
import org.qubership.cloud.encryption.key.KeyStore;
import org.qubership.cloud.encryption.key.KeyStoreRepository;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Set;

public abstract class AbstractJCACryptoProvider extends AbstractCryptoProvider {
    @Nonnull
    private final KeyStore keyStore;

    private final KeyStoreRepository keyStoreRepository;


    public AbstractJCACryptoProvider(@Nonnull final KeyStore keyStore) {
        this(keyStore, null);
    }

    public AbstractJCACryptoProvider(@Nonnull final KeyStore keyStore, final KeyStoreRepository keyStoreRepository) {
        this.keyStore = Preconditions.checkNotNull(keyStore, "KeyStore can't be null");
        this.keyStoreRepository = keyStoreRepository;
    }



    @Nonnull
    protected abstract String getDefaultKeyAlias();

    @Nonnull
    protected abstract String getDefaultAlgorithm();

    @Nonnull
    protected AliasedKey getKeyFromRequest(@Nonnull final CryptoRequest request) {
        if (request.getKey().isPresent()) {
            return new ImmutableAliasedKey(request.getKey().get());
        } else {
            final String alias;
            if (request.getKeyAlias().isPresent()) {
                alias = request.getKeyAlias().get();
            } else {
                alias = getDefaultKeyAlias();
            }

            return getKeyFromKeyStore(request, alias);
        }
    }

    protected AliasedKey getKeyFromKeyStore(@Nonnull CryptoRequest request, String alias) {
        AliasedKey key;

        key = getKeyFromKeyStoreInternal(alias);

        if (key == null) {
            throw new NotExistsSecurityKey(
                    String.format("Key can't be find by alias '%s' in KeyStore %s for process CryptoRequest: %s", alias,
                            keyStore, request));
        }
        return key;
    }

    protected AliasedKey getKeyFromKeyStoreInternal(String alias) {
        AliasedKey key;
        key = keyStore.getAliasedKey(alias);

        if (key == null && keyStoreRepository != null) {
            key = tryFindKeyFromAllKeyStores(alias);
        }
        return key;
    }

    protected AliasedKey tryFindKeyFromAllKeyStores(String alias) {

        AliasedKey key;
        Set<String> keyStoresIdentities = keyStoreRepository.getKeyStoresIdentities();
        keyStoresIdentities.remove(keyStore.getIdentity());

        for (String keyStoreName : keyStoresIdentities) {
            KeyStore anotherKeyStore = keyStoreRepository.getKeyStoreByIdentity(keyStoreName);
            if (anotherKeyStore != null) {
                key = anotherKeyStore.getAliasedKey(alias);
                if (key != null) {
                    return key;
                }
            }
        }

        return null;
    }


    /**
     * @param request not null request from that need read all required parameters
     * @return not initialize cipher
     */
    @Nonnull
    protected Cipher getCipherInstance(@Nonnull CryptoRequest request) {
        final String algorithm = defineAlgorithm(request);
        return getCipherInstance(algorithm, request.getProvider().orNull());
    }

    /**
     * @param algorithm algorithm with that need initialize cipher
     * @return not initialize cipher
     */
    @Nonnull
    protected Cipher getCipherInstance(@Nonnull String algorithm, @Nullable String provider) {
        try {
            return provider != null ? Cipher.getInstance(algorithm, provider) : Cipher.getInstance(algorithm);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | NoSuchProviderException e) {
            throw new IllegalCryptoParametersException("Exception occurs during initialize cipher for algorithm: "
                    + algorithm + " and provider: " + provider, e);
        }
    }

    protected String defineAlgorithm(@Nonnull CryptoRequest request) {
        final String algorithm;
        if (request.getAlgorithm().isPresent()) {
            algorithm = request.getAlgorithm().get();
        } else {
            algorithm = getDefaultAlgorithm();
        }
        return algorithm;
    }

    @Nonnull
    protected EncryptedData jcaEncrypt(EncryptionRequest request) {
        try {
            final AliasedKey key = getKeyFromRequest(request);
            final String algorithm = defineAlgorithm(request);

            Cipher cipher = getCipherInstance(algorithm, request.getProvider().orNull());
            if (request.getIV().isPresent()) {
                cipher.init(Cipher.ENCRYPT_MODE, key.getKey(), new IvParameterSpec(request.getIV().get()));
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, key.getKey());
            }

            byte[] cryptedData = cipher.doFinal(request.getPlainText());

            return new EncryptedDataBuilder().setUsedAlgorithm(algorithm).setUsedKey(key)
                    .setInitializedVector(cipher.getIV()).setEncryptedText(cryptedData).build();
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IllegalCryptoParametersException(
                    "Exception occurs during initialize encryption parameters from request: " + request, e);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new EncryptException("Exception occurs during encryption plaint ext from request:" + request, e);
        }
    }

    protected byte[] jcaDecrypt(DecryptionRequest request) {
        try {
            final AliasedKey key = getKeyFromRequest(request);

            Cipher cipher = getCipherInstance(request);
            if (request.getIV().isPresent()) {
                cipher.init(Cipher.DECRYPT_MODE, key.getKey(), new IvParameterSpec(request.getIV().get()));
            } else {
                cipher.init(Cipher.DECRYPT_MODE, key.getKey());
            }

            return cipher.doFinal(request.getEncryptedText());
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IllegalCryptoParametersException(
                    "Exception occurs during initialize parameters for decryption from request: " + request, e);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new DecryptException("Exception occurs during decrypt data from request:" + request, e);
        }
    }

}

