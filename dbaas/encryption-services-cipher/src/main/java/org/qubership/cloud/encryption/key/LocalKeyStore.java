package org.qubership.cloud.encryption.key;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import org.qubership.cloud.encryption.cipher.exception.BadKeyPasswordException;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.keystore.type.LocalKeystoreConfig;
import org.qubership.cloud.encryption.key.exception.IllegalKeystoreConfigurationException;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocalKeyStore implements KeyStore {
    private final String location;
    private final String identity;
    private final boolean deprecated;
    private Map<String, KeyConfig> protectedKeys;

    private final java.security.KeyStore jcaKeystore;

    public LocalKeyStore(@Nonnull final LocalKeystoreConfig config) {
        Preconditions.checkNotNull(config, "LocalKeystoreConfig can't be null");
        this.identity = config.getKeystoreIdentifier();
        this.location = config.getLocation();
        this.deprecated = config.isDeprecated();
        this.protectedKeys = associateWithAliases(config.getKeys());

        try {
            jcaKeystore = java.security.KeyStore.getInstance(config.getKeystoreType());
            try (InputStream in = new FileInputStream(config.getLocation())) {
                char[] password = config.getPassword() == null ? new char[0] : config.getPassword().toCharArray();
                jcaKeystore.load(in, password);
            }
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalKeystoreConfigurationException("Configuration " + config + " can't be parse correct", e);
        }
    }

    @Nonnull
    @Override
    public String getIdentity() {
        return identity;
    }

    @Nullable
    @Override
    public Key getKeyByAlias(@Nonnull String aliasName, String password) {
        try {
            char[] pwd = StringUtils.isEmpty(password) ? new char[0] : password.toCharArray();
            return jcaKeystore.getKey(aliasName, pwd);
        } catch (UnrecoverableKeyException e) {
            throw new BadKeyPasswordException(
                    "Password for the Key with alias " + aliasName
                            + " either is wrong or is not provided. Ensure that the keystore configuration is correct.",
                    e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Key by alias " + aliasName + " can not by find in keystore: " + this, e);
        }
    }

    @Nullable
    @Override
    public Key getKeyByAlias(@Nonnull String aliasName) {
        KeyConfig keyConfig = protectedKeys.get(aliasName);
        String password = keyConfig != null ? keyConfig.getPassword() : null;
        return getKeyByAlias(aliasName, password);
    }

    @Override
    public <T extends Key> T getKeyByAlias(@Nonnull String aliasName, @Nonnull Class<T> keyType) {
        Key key = getKeyByAlias(aliasName);
        if (key != null && keyType.isAssignableFrom(key.getClass())) {
            return keyType.cast(key);
        }

        return null;
    }

    @Nullable
    @Override
    public AliasedKey getAliasedKey(@Nonnull String aliasName) {

        Key key = getKeyByAlias(aliasName);

        if (key == null) {
            return null;
        }

        KeyConfig keyConfig = protectedKeys.get(aliasName);

        boolean isKeyDeprecated = false;
        if (deprecated) {
            isKeyDeprecated = true;
        } else {
            isKeyDeprecated = keyConfig != null ? keyConfig.isDeprecated() : false;
        }
        return new ImmutableAliasedKey(key, aliasName, isKeyDeprecated);
    }

    @Override
    public boolean isDeprecated() {
        return deprecated;
    }

    @Override
    public List<String> getAliases() {
        try {
            return Collections.list(jcaKeystore.aliases());
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Keystore was not initialized: " + this, e);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("location", location).add("identity", identity)
                .add("deprecated", deprecated).toString();
    }

    private Map<String, KeyConfig> associateWithAliases(List<KeyConfig> keys) {
        Map<String, KeyConfig> keyPasswords = new HashMap<>(keys.size());
        for (KeyConfig keyConfig : keys) {
            keyPasswords.put(keyConfig.getAlias(), keyConfig);
        }

        return keyPasswords;
    }
}

