package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.exceptions.DecryptException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Encrypts the secrets DBaaS keeps in its registry.
 *
 * <p>Encrypted values carry the algorithm, the key alias and, where the algorithm uses one, the initialization vector
 * they were produced with, in the envelope
 * {@code {v2c}{algorithm}{keyAlias}{iv}{base64(encrypted data)}}. Reading those parts back from the value lets a value
 * written by an older release, or by a key that is no longer the default, still be decrypted.
 *
 * <p>Keys come from {@code KS_*} environment variables and from files with the same names under
 * {@code /etc/secrets/pod-secrets}; the alias is the name after the prefix, and the environment wins over the file.
 * When {@code SYM_KEY} holds a key, every {@code KS_*} value is itself encrypted with it and is unwrapped before use.
 */
@ApplicationScoped
public class PasswordCipher {

    private static final String AES = "AES";
    private static final String DEFAULT_KEY_ALIAS = "DEFAULT_KEY";
    private static final String KEY_PREFIX = "KS_";
    private static final String MASTER_KEY_NAME = "SYM_KEY";
    private static final String POD_SECRETS_PATH = "/etc/secrets/pod-secrets";

    private static final Pattern ENVELOPE = Pattern.compile(
            "^\\{v2c\\}(\\{(?<algorithm>[^\\{\\}]*)\\}){1}(\\{(?<keyAlias>[^\\{\\}]*)\\}){1}"
                    + "(\\{(?<iv>[a-zA-Z0-9\\=\\/\\+]+)\\}){0,1}(\\{(?<cryptData>[a-zA-Z0-9\\=\\/\\+]+)\\}){1}$");

    private final Map<String, String> keyMaterial;
    private final SecretKey masterKey;

    @Inject
    public PasswordCipher() {
        this(readKeyMaterial(), readMasterKey());
    }

    PasswordCipher(Map<String, String> keyMaterial, SecretKey masterKey) {
        this.keyMaterial = Map.copyOf(keyMaterial);
        this.masterKey = masterKey;
    }

    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(AES);
            cipher.init(Cipher.ENCRYPT_MODE, key(DEFAULT_KEY_ALIAS));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder("{v2c}{").append(AES).append("}{").append(DEFAULT_KEY_ALIAS).append('}');
            if (cipher.getIV() != null) {
                result.append('{').append(Base64.encodeBase64String(cipher.getIV())).append('}');
            }
            return result.append('{').append(Base64.encodeBase64String(encrypted)).append('}').toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt value with alias " + DEFAULT_KEY_ALIAS, e);
        }
    }

    public String decrypt(String encryptedText) {
        Matcher matcher = ENVELOPE.matcher(encryptedText);
        if (!matcher.find()) {
            throw new DecryptException("Value is not in the encrypted format written by DBaaS");
        }
        byte[] iv = decodeBase64(matcher.group("iv"));
        try {
            Cipher cipher = Cipher.getInstance(matcher.group("algorithm"));
            SecretKey key = key(matcher.group("keyAlias"));
            if (iv != null) {
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            } else {
                cipher.init(Cipher.DECRYPT_MODE, key);
            }
            return new String(cipher.doFinal(decodeBase64(matcher.group("cryptData"))), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new DecryptException("Unable to decrypt value with alias " + matcher.group("keyAlias"), e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && ENVELOPE.matcher(value).find();
    }

    private SecretKey key(String alias) {
        String material = keyMaterial.get(alias);
        if (material == null) {
            throw new IllegalStateException("No encryption key is configured for alias " + alias);
        }
        byte[] decoded = java.util.Base64.getDecoder().decode(material.trim());
        try {
            if (masterKey == null) {
                return new SecretKeySpec(decoded, AES);
            }
            Cipher unwrapper = Cipher.getInstance(AES);
            unwrapper.init(Cipher.DECRYPT_MODE, masterKey);
            return new SecretKeySpec(unwrapper.doFinal(decoded), AES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to restore the encryption key configured for alias " + alias, e);
        }
    }

    private static Map<String, String> readKeyMaterial() {
        Map<String, String> material = new HashMap<>();
        System.getenv().forEach((name, value) -> {
            if (name.startsWith(KEY_PREFIX)) {
                material.put(name.substring(KEY_PREFIX.length()), value.trim());
            }
        });

        Path secretsDir = Path.of(POD_SECRETS_PATH);
        if (Files.isDirectory(secretsDir)) {
            try (Stream<Path> files = Files.list(secretsDir)) {
                files.filter(file -> file.getFileName().toString().startsWith(KEY_PREFIX))
                        .forEach(file -> {
                            String value = readSecretFile(file);
                            if (value != null) {
                                material.putIfAbsent(file.getFileName().toString().substring(KEY_PREFIX.length()), value);
                            }
                        });
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read encryption keys from " + POD_SECRETS_PATH, e);
            }
        }
        return Map.copyOf(material);
    }

    private static SecretKey readMasterKey() {
        String value = readSecret(MASTER_KEY_NAME);
        if (value == null || value.isBlank()) {
            return null;
        }
        return new SecretKeySpec(java.util.Base64.getDecoder().decode(value.trim()), AES);
    }

    private static String readSecret(String name) {
        String value = System.getenv(name);
        if (value != null) {
            return value;
        }
        Path file = Path.of(POD_SECRETS_PATH, name);
        return Files.exists(file) ? readSecretFile(file) : null;
    }

    private static String readSecretFile(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Decodes the parts of a stored envelope. Earlier releases wrote them with the commons-codec encoder, whose decoder
     * also accepts input the JDK one rejects, so values already in the registry keep decoding.
     */
    private static byte[] decodeBase64(String value) {
        return value == null ? null : Base64.decodeBase64(value);
    }
}
