package org.qubership.cloud.encryption.key;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.qubership.cloud.encryption.config.keystore.KeystoreSubsystemConfig;
import org.qubership.cloud.encryption.config.keystore.type.EnvironmentKeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public class KeyStoreRepositoryImpl implements KeyStoreRepository {
    private final String defaultKeyStoreId;
    private final Map<String, KeyStore> keystores = Maps.newConcurrentMap();

    public KeyStoreRepositoryImpl(@Nonnull final KeystoreSubsystemConfig config) {
        Preconditions.checkNotNull(config, "KeystoreSubsystemConfig can't be null");
        if (config.getDefaultKeyStore() == null) {
            defaultKeyStoreId = "NotExistsConfiguredKeyStore";
        } else {
            defaultKeyStoreId = config.getDefaultKeyStore().getKeystoreIdentifier();
        }

        for (KeystoreConfig keystoreConfig : config.getKeyStores()) {
            KeyStore keyStore;
            if (keystoreConfig instanceof LocalKeystoreConfig) {
                keyStore = new LocalKeyStore((LocalKeystoreConfig) keystoreConfig);
            } else if (keystoreConfig instanceof EnvironmentKeystoreConfig) {
                keyStore = new EnvironmentKeyStore((EnvironmentKeystoreConfig) keystoreConfig);
            } else {
                throw new UnsupportedOperationException("Configuration " + keystoreConfig + " not supported yet");
            }

            keystores.put(keyStore.getIdentity(), keyStore);
        }
    }

    @Nullable
    @Override
    public KeyStore getKeyStoreByIdentity(@Nonnull String identity) {
        return keystores.get(identity);
    }

    @Nullable
    @Override
    public KeyStore getDefaultKeystore() {
        return getKeyStoreByIdentity(defaultKeyStoreId);
    }

    @Nonnull
    @Override
    public Set<String> getKeyStoresIdentities() {
        return keystores.keySet();
    }
}

