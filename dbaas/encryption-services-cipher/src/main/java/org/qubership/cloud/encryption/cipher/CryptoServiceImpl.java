package org.qubership.cloud.encryption.cipher;

import com.google.common.collect.Lists;
import org.qubership.cloud.encryption.cipher.dsl.decrypt.ChainedDecryptionRequest;
import org.qubership.cloud.encryption.cipher.dsl.decrypt.ChainedDecryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.dsl.encrypt.ChainedEncryptionRequest;
import org.qubership.cloud.encryption.cipher.dsl.encrypt.ChainedEncryptionRequestBuilder;
import org.qubership.cloud.encryption.cipher.exception.NotFoundSuitableCryptoProvider;
import org.qubership.cloud.encryption.cipher.provider.CryptoProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

@ThreadSafe
public class CryptoServiceImpl implements CryptoService {
    @Nonnull
    private final CryptoProvider defaultCryptoProvider;
    @Nonnull
    private final List<? extends CryptoProvider> availableCryptoProviders;

    public CryptoServiceImpl(@Nonnull CryptoProvider defaultCryptoProvider,
            @Nonnull List<? extends CryptoProvider> availableCryptoProviders) {
        checkNotNull(defaultCryptoProvider, "CryptoProvider that work like default can't be null");
        checkNotNull(availableCryptoProviders, "List available CryptoProviders can't be null");
        this.defaultCryptoProvider = defaultCryptoProvider;
        this.availableCryptoProviders = Lists.newArrayList(availableCryptoProviders);
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
        checkNotNull(request, "EncryptionRequest can't be null");
        CryptoProvider provider = choseSuitableProvider(request);

        return provider.encrypt(request);
    }

    @Nonnull
    private CryptoProvider choseSuitableProvider(@Nonnull EncryptionRequest request) {
        Set<CryptoParameter> params = CryptoParameter.whatParameterDefined(request);

        CryptoProvider provider = getProviderBySupportFeatures(params);

        if (provider == null) {
            throw new NotFoundSuitableCryptoProvider(request);
        }
        return provider;
    }

    @Nullable
    private CryptoProvider getProviderBySupportFeatures(Set<CryptoParameter> params) {
        CryptoProvider provider = null;

        if (defaultCryptoProvider.getSupportsCryptoParameters().containsAll(params)) {
            provider = defaultCryptoProvider;
        } else {
            for (CryptoProvider checkProvider : availableCryptoProviders) {
                if (checkProvider.getSupportsCryptoParameters().containsAll(params)) {
                    provider = checkProvider;
                    break;
                }
            }
        }
        return provider;
    }

    @Nonnull
    private CryptoProvider choseSuitableProvider(@Nonnull DecryptionRequest request) {
        Set<CryptoParameter> params = CryptoParameter.whatParameterDefined(request);

        CryptoProvider provider = getProviderBySupportFeatures(params);

        if (provider == null) {
            throw new NotFoundSuitableCryptoProvider(request);
        }
        return provider;
    }

    @Nonnull
    @Override
    public DecryptResult decrypt(@Nonnull String data) {
        checkNotNull(data, "Data for decrypt can't be null");
        for (CryptoProvider provider : availableCryptoProviders) {
            if (provider.isKnowEncryptedFormat(data)) {
                return provider.decrypt(data);
            }
        }

        // provider not fount, data not contains info about algorithm
        return decryptDSLRequest().decrypt(data);
    }

    @Nonnull
    @Override
    public DecryptResult decrypt(@Nonnull DecryptionRequest request) {
        checkNotNull(request, "DecryptionRequest can't be null");
        CryptoProvider provider = choseSuitableProvider(request);
        return provider.decrypt(request);
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
        checkNotNull(encryptedData, "Data can't be null");
        for (CryptoProvider provider : availableCryptoProviders) {
            if (provider.isKnowEncryptedFormat(encryptedData)) {
                return provider.getEncryptedMetaInfo(encryptedData);
            }
        }

        return null;
    }
}

