package org.qubership.cloud.encryption.config.xml;

import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.EncryptionConfiguration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ConfigurationCryptoProvider {

    @Nonnull
    EncryptionConfiguration cryptSecureParameters(@Nonnull EncryptionConfiguration config,
            @Nonnull ConfigurationParser parser);

    @Nonnull
    DecryptionResult decryptSecureParameters(@Nonnull EncryptionConfiguration config,
            @Nullable ConfigurationParser parser);

    public static class DecryptionResult {
        @Nonnull
        private final EncryptionConfiguration configuration;
        private final boolean notEncryptedSecureParameterPresent;

        public DecryptionResult(@Nonnull final EncryptionConfiguration configuration,
                final boolean notEncryptedSecureParameterPresent) {
            this.configuration = configuration;
            this.notEncryptedSecureParameterPresent = notEncryptedSecureParameterPresent;
        }

        @Nonnull
        public EncryptionConfiguration getConfiguration() {
            return configuration;
        }

        public boolean isNotEncryptedSecureParameterPresent() {
            return notEncryptedSecureParameterPresent;
        }
    }
}
