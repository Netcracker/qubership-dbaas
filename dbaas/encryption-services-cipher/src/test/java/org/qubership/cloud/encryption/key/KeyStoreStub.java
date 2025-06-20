package org.qubership.cloud.encryption.key;

import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.security.Key;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@NotThreadSafe
public class KeyStoreStub implements KeyStore {
    private final String keystoreName;

    @Nonnull
    private final Map<String, ? extends Key> defaultKeys;
    private final Map<String, Key> keyStore = Maps.newHashMap();

    private boolean deprecated;

    public KeyStoreStub() {
        defaultKeys = Collections.emptyMap();
        keystoreName = "" + System.nanoTime();
        deprecated = false;
    }

    public KeyStoreStub(@Nonnull final String keystoreName) {
        this.keystoreName = keystoreName;
        defaultKeys = Collections.emptyMap();
    }

    public KeyStoreStub(@Nonnull final Map<String, ? extends Key> defaultKeys) {
        this.defaultKeys = defaultKeys;
        keyStore.putAll(defaultKeys);
        keystoreName = "" + System.nanoTime();
    }

    public KeyStoreStub(@Nonnull final String keystoreName, @Nonnull final Map<String, ? extends Key> defaultKeys) {
        this.defaultKeys = defaultKeys;
        this.keyStore.putAll(defaultKeys);
        this.keystoreName = keystoreName;
    }

    @Nonnull
    @Override
    public String getIdentity() {
        return keystoreName;
    }

    @Nullable
    @Override
    public Key getKeyByAlias(@Nonnull String aliasName, String password) {
        return keyStore.get(aliasName);
    }

    @Nullable
    @Override
    public Key getKeyByAlias(@Nonnull String aliasName) {
        return keyStore.get(aliasName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Key> T getKeyByAlias(@Nonnull String aliasName, @Nonnull Class<T> keyType) {
        Key findKey = getKeyByAlias(aliasName);
        if (findKey != null && keyType.isAssignableFrom(findKey.getClass())) {
            return (T) findKey;
        }

        return null;
    }

    public void registerKeyByAlias(@Nonnull String alias, @Nonnull Key key) {
        keyStore.put(alias, key);
    }

    public void registerKeyByAlias(@Nonnull Map<String, ? extends Key> keysForRegister) {
        keyStore.putAll(keysForRegister);
    }

    public void clear() {
        keyStore.clear();
        keyStore.putAll(defaultKeys);
    }

    @Nullable
    @Override
    public AliasedKey getAliasedKey(@Nonnull String aliasName) {
        Key key = getKeyByAlias(aliasName);

        if (key == null) {
            return null;
        }

        return new ImmutableAliasedKey(key, aliasName, deprecated);
    }

    @Override
    public boolean isDeprecated() {
        return deprecated;
    }

    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    @Override
    public List<String> getAliases() {
        return new ArrayList<>(defaultKeys.keySet());
    }
}

