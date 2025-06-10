package org.qubership.cloud.encryption.key;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import org.qubership.cloud.encryption.config.keystore.type.EnvironmentKeystoreConfig;
import org.qubership.cloud.encryption.config.keystore.type.KeyConfig;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.KeyXmlConf;
import org.qubership.cloud.encryption.key.exception.IllegalKeystoreConfigurationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Base64.Decoder;

public class EnvironmentKeyStore implements KeyStore {
    private final String identity;
    private final boolean deprecated;
    private final String prefix;
    private final boolean encrypted;
    private final String passwordVarName;
    private Map<String, KeyConfig> protectedKeys;

    public EnvironmentKeyStore(EnvironmentKeystoreConfig keystoreConfig) {
        this.identity = keystoreConfig.getKeystoreIdentifier();
        this.deprecated = keystoreConfig.isDeprecated();
        this.protectedKeys = associateWithAliases(keystoreConfig);
        this.prefix = keystoreConfig.getPrefix();
        this.encrypted = keystoreConfig.isEncrypted();
        this.passwordVarName = keystoreConfig.getPasswordVar();
    }

    @Override
    public String getIdentity() {
        return identity;
    }

    @Override
    public Key getKeyByAlias(String aliasName, String password) {
        if (password == null)
            return null;

        final Decoder decoder = Base64.getDecoder();
        byte[] keyBytes;
        if (encrypted) {
            final String encPassword = Strings.nullToEmpty(System.getenv(passwordVarName)).trim();

            final byte[] encPasswordBytes = decoder.decode(encPassword);
            final Key encPasswordKey = new SecretKeySpec(encPasswordBytes, "AES");
            try {
                Cipher cipher = Cipher.getInstance("AES");
                cipher.init(Cipher.DECRYPT_MODE, encPasswordKey);

                keyBytes = decoder.decode(password);
                keyBytes = cipher.doFinal(keyBytes);

            } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException
                    | BadPaddingException e) {
                // TODO what's about to split handling exceptions to separate blocks?!
                throw new IllegalKeystoreConfigurationException("Can't restore key by alias [" + aliasName + "]!", e);
            }
        } else if (!Strings.isNullOrEmpty(password)) {
            keyBytes = decoder.decode(password);
        } else {
            throw new IllegalKeystoreConfigurationException("Not found key with alias [" + aliasName + "]!");
        }

        return new SecretKeySpec(keyBytes, "AES");
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
        return new ArrayList<>(protectedKeys.keySet());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("identity", identity).add("deprecated", deprecated).toString();
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    private Map<String, KeyConfig> associateWithAliases(EnvironmentKeystoreConfig keystoreConfig) {
        final Map<String, KeyConfig> keyPasswords = new HashMap<>();
        final String configPrefix = keystoreConfig.getPrefix();
        final int prefixLength = configPrefix.length();
        final Map<String, String> envMap = System.getenv();
        envMap.forEach((k, v) -> {
            if (k.startsWith(configPrefix)) {
                String alias = k.substring(prefixLength);
                KeyXmlConf keyConfig = new KeyXmlConf();
                keyConfig.setAlias(alias);
                keyConfig.setDeprecated(false);

                String password = Strings.nullToEmpty(v).trim();
                keyConfig.setPassword(password);

                keyPasswords.put(alias, keyConfig);
            }
        });

        return keyPasswords;
    }
}
