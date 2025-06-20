package org.qubership.cloud.encryption.cipher;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Set;

/**
 * Available list parameters that can be specify for {@link CryptoRequest}.
 */
public enum CryptoParameter {
    ALGORITHM,

    PROVIDER,

    KEY,

    KEY_ALIS,

    INITIALIZED_VECTOR;

    /**
     * Calculate what parameters was specify explicitly in {@link EncryptionRequest} request.
     * 
     * @param request not null request for that need define list set parameters
     * @return not null set with all {@link CryptoParameter} that set explicit in request, if explicit parameters empty
     *         result set will be empty
     */
    @Nonnull
    public static Set<CryptoParameter> whatParameterDefined(@Nonnull EncryptionRequest request) {
        return whatCommonParametersDefine(request);
    }

    /**
     * Calculate what parameters was specify explicitly in {@link DecryptionRequest} request.
     * 
     * @param request not null request for that need define list set parameters
     * @return not null set with all {@link CryptoParameter} that set explicit in request, if explicit parameters empty
     *         result set will be empty
     */
    @Nonnull
    public static Set<CryptoParameter> whatParameterDefined(@Nonnull DecryptionRequest request) {
        return whatCommonParametersDefine(request);
    }

    private static EnumSet<CryptoParameter> whatCommonParametersDefine(@Nonnull CryptoRequest request) {
        EnumSet<CryptoParameter> result = EnumSet.noneOf(CryptoParameter.class);

        if (request.getKey().isPresent()) {
            result.add(KEY);
        }

        if (request.getKeyAlias().isPresent()) {
            result.add(KEY_ALIS);
        }

        if (request.getAlgorithm().isPresent()) {
            result.add(ALGORITHM);
        }

        if (request.getIV().isPresent()) {
            result.add(INITIALIZED_VECTOR);
        }

        if (request.getProvider().isPresent()) {
            result.add(PROVIDER);
        }

        return result;
    }
}

