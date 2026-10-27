package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.exceptions.DecryptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasswordCipherTest {
    /**
     * Values written by the encryption library that earlier DBaaS releases shipped, captured through the wiring those
     * releases used: the AES algorithm, the DEFAULT_KEY alias, base64 key material. They are the compatibility contract
     * in both directions: what an earlier release wrote has to decrypt here, and what this writes has to decrypt there.
     *
     * <p>The keys are fixtures, not credentials, and each is built here from the text it encodes. The payloads are
     * ciphertext, so they stay as captured, and they follow from those keys alone because the mode the aggregator
     * writes in is deterministic: AES/ECB/PKCS5Padding with the decoded key and no IV, plaintext read as UTF-8,
     * ciphertext in unchunked base64, assembled as {@code {v2c}{AES}{DEFAULT_KEY}{payload}}. Any payload can be
     * reproduced without this repository:
     *
     * <pre>{@code
     * printf 'simplePassword123' \
     *   | openssl enc -aes-128-ecb -K 30313233343536373839616263646566 -nosalt -a -A
     * # -K is the key text "0123456789abcdef" in hex; the result is the payload of DEFAULT_SETTINGS_VALUES[0]
     * }</pre>
     */
    private static final String KEY = base64("0123456789abcdef");
    private static final String MASTER_KEY = base64("fedcba9876543210");
    /**
     * {@link #KEY} encrypted with {@link #MASTER_KEY}, the way a key is stored when SYM_KEY is configured:
     *
     * <pre>{@code
     * printf '0123456789abcdef' \
     *   | openssl enc -aes-128-ecb -K 66656463626139383736353433323130 -nosalt -a -A
     * }</pre>
     */
    private static final String WRAPPED_KEY = "PVuaCIiaKQhblgFCbVMTTpet090Hk8duEXLYgdGOcwc=";
    private static final String OTHER_KEY = base64("0123456789ABCDEF");
    /** A value of {@link #DEFAULT_SETTINGS_VALUES} with one byte added to the payload, breaking the AES block size. */
    private static final String CORRUPTED_VALUE =
            "{v2c}{AES}{DEFAULT_KEY}{8WI3xsBb6DbPUaj/G6hkWsMQxPuKn/LBXajeJdXIym0AAA=}";

    private static final PasswordCipher CIPHER =
            new PasswordCipher(Map.of("DEFAULT_KEY", KEY, "LEGACY_KEY", KEY), null);

    private record Stored(String plain, String envelope) {
    }

    private static final String ROTATED_ALIAS_VALUE =
            new Stored("rotatedKeyPassword", "{v2c}{AES}{LEGACY_KEY}{/1+RLnHj9moxXstfYJ15AVJcY8w1AcZRCQ3G6HTODJs=}")
                    .envelope();

    private static final Stored[] DEFAULT_SETTINGS_VALUES = {
            new Stored("simplePassword123", "{v2c}{AES}{DEFAULT_KEY}{8WI3xsBb6DbPUaj/G6hkWsMQxPuKn/LBXajeJdXIym0=}"),
            new Stored("", "{v2c}{AES}{DEFAULT_KEY}{N3Ii4GGpJMWRzZwn6hY+1A==}"),
            new Stored("p\u00e4ssw\u00f6rd-unicode",
                    "{v2c}{AES}{DEFAULT_KEY}{q2fl9s77QwHhY317Py6RaWXxqYS7jdTemGGpiACNgmc=}"),
            new Stored("sp ec!@#$%^&*()_+-={}[]|:;\"'<>,.?/~`",
                    "{v2c}{AES}{DEFAULT_KEY}{kHYJFljhuCAPY7q9Mk+a8Hz+otValSYgP7sfscYABhQPn5KKb7Eh2IVor6jhEltm}"),
            new Stored("L".repeat(64),
                    "{v2c}{AES}{DEFAULT_KEY}{SMgigX0D+WDhFikmRE2hbUjIIoF9A/lg4RYpJkRNoW1IyCKBfQP5YOEWKS"
                            + "ZETaFtSMgigX0D+WDhFikmRE2hbTdyIuBhqSTFkc2cJ+oWPtQ=}"),
    };

    /**
     * The values above, which the settings of a current release produce, plus three that no release wrote but the
     * format allows: two encrypted with AES/CBC/PKCS5Padding, which fills in the optional initialization vector group,
     * and one stored under the alias of a key that a later key replaced as the default. A reader has to accept all of
     * them, a writer of this class produces none of them.
     */
    private static final Stored[] HISTORICAL_VALUES = {
            DEFAULT_SETTINGS_VALUES[0],
            DEFAULT_SETTINGS_VALUES[1],
            DEFAULT_SETTINGS_VALUES[2],
            DEFAULT_SETTINGS_VALUES[3],
            DEFAULT_SETTINGS_VALUES[4],
            new Stored("legacyCbcPassword", "{v2c}{AES/CBC/PKCS5Padding}{DEFAULT_KEY}{" + base64("abcdefghijklmnop") + "}"
                    + "{u5Vg9wYvYBDqenukSrYF8Q0wLjBDai4IRx0Jmr3kDww=}"),
            new Stored("", "{v2c}{AES/CBC/PKCS5Padding}{DEFAULT_KEY}{" + base64("abcdefghijklmnop") + "}"
                    + "{gdhetxVyVGOIr0wMPWN5FA==}"),
            new Stored("rotatedKeyPassword", ROTATED_ALIAS_VALUE),
    };

    @Test
    void testEncrypt_shouldWriteTheSameBytesAsEarlierReleases() {
        for (Stored stored : DEFAULT_SETTINGS_VALUES) {
            assertEquals(stored.envelope(), CIPHER.encrypt(stored.plain()));
        }
    }

    @Test
    void testDecrypt_shouldReadValuesWrittenByEarlierReleases() {
        for (Stored stored : HISTORICAL_VALUES) {
            assertEquals(stored.plain(), CIPHER.decrypt(stored.envelope()), stored.envelope());
        }
    }

    @Test
    void testEncryptDecrypt_shouldUseTheSameBytes_whenKeyMaterialIsWrappedByTheMasterKey() {
        PasswordCipher wrapped = new PasswordCipher(Map.of("DEFAULT_KEY", WRAPPED_KEY, "LEGACY_KEY", WRAPPED_KEY),
                masterKey());
        for (Stored stored : HISTORICAL_VALUES) {
            assertEquals(stored.plain(), wrapped.decrypt(stored.envelope()), stored.envelope());
        }
        for (Stored stored : DEFAULT_SETTINGS_VALUES) {
            assertEquals(stored.envelope(), wrapped.encrypt(stored.plain()));
        }
    }

    @Test
    void testEncrypt_shouldKeepUsingTheDefaultAlias_whenAnOlderAliasIsStillConfigured() {
        assertEquals("DEFAULT_KEY", aliasOf(CIPHER.encrypt("rotatedKeyPassword")));
        assertEquals("LEGACY_KEY", aliasOf(ROTATED_ALIAS_VALUE));
    }

    @Test
    void testIsEncrypted_shouldRecogniseEveryStoredFormatOnly() {
        for (Stored stored : HISTORICAL_VALUES) {
            assertTrue(CIPHER.isEncrypted(stored.envelope()), stored.envelope());
            assertFalse(CIPHER.isEncrypted(stored.plain()), stored.envelope());
        }
    }

    @Test
    void testIsEncrypted_shouldRecogniseAValueWithATrailingLineBreak() {
        assertTrue(CIPHER.isEncrypted(DEFAULT_SETTINGS_VALUES[0].envelope() + "\n"));
    }

    @Test
    void testIsEncrypted_shouldReturnFalseForNull() {
        assertFalse(CIPHER.isEncrypted(null));
    }

    @Test
    void testDecrypt_shouldFail_whenValueIsNotAnEnvelope() {
        assertThrows(DecryptException.class, () -> CIPHER.decrypt("simplePassword123"));
    }

    @Test
    void testDecrypt_shouldFail_whenValueIsNull() {
        // PasswordEncryption reads this failure as a record that carries no stored password, so the type matters: a
        // DecryptException here would make a backup generate a new password instead of reporting the corrupt record.
        assertThrows(NullPointerException.class, () -> CIPHER.decrypt(null));
    }

    @Test
    void testDecrypt_shouldFail_whenNoKeyIsConfiguredForTheAliasOfTheValue() {
        assertThrows(IllegalStateException.class,
                () -> CIPHER.decrypt("{v2c}{AES}{ABSENT_KEY}{8WI3xsBb6DbPUaj/G6hkWsMQxPuKn/LBXajeJdXIym0=}"));
    }

    @Test
    void testEncrypt_shouldFail_whenNoKeyIsConfigured() {
        assertThrows(IllegalStateException.class, () -> new PasswordCipher(Map.of(), null).encrypt("plain"));
    }

    @Test
    void testDecrypt_shouldFail_whenKeyIsNotTheOneThatEncryptedTheValue() {
        PasswordCipher other = new PasswordCipher(Map.of("DEFAULT_KEY", OTHER_KEY), null);
        assertThrows(DecryptException.class, () -> other.decrypt(DEFAULT_SETTINGS_VALUES[0].envelope()));
    }

    @Test
    void testDecrypt_shouldFail_whenPayloadOfTheValueIsCorrupted() {
        assertThrows(DecryptException.class, () -> CIPHER.decrypt(CORRUPTED_VALUE));
    }

    @Test
    void testEncrypt_shouldFail_whenKeyMaterialIsNotBase64() {
        assertThrows(IllegalStateException.class,
                () -> new PasswordCipher(Map.of("DEFAULT_KEY", "not base64!"), null).encrypt("plain"));
    }

    @Test
    void testReadKeyMaterial_shouldTakeAliasesFromFileNamesWithoutThePrefix(@TempDir Path secretsDir)
            throws IOException {
        Files.writeString(secretsDir.resolve("KS_DEFAULT_KEY"), KEY + "\n");
        Files.writeString(secretsDir.resolve("KS_LEGACY_KEY"), KEY);
        Files.writeString(secretsDir.resolve("SYM_KEY"), MASTER_KEY);
        Files.writeString(secretsDir.resolve("POSTGRES_PASSWORD"), "ignored");

        assertEquals(Map.of("DEFAULT_KEY", KEY, "LEGACY_KEY", KEY), PasswordCipher.readKeyMaterial(secretsDir));
    }

    @Test
    void testReadKeyMaterial_shouldReturnEmptyMap_whenSecretsDirectoryIsAbsent(@TempDir Path secretsDir) {
        assertEquals(Map.of(), PasswordCipher.readKeyMaterial(secretsDir.resolve("pod-secrets")));
    }

    @Test
    void testReadMasterKey_shouldBeAbsent_whenFileIsMissingOrBlank(@TempDir Path secretsDir) throws IOException {
        assertNull(PasswordCipher.readMasterKey(secretsDir));

        Files.writeString(secretsDir.resolve("SYM_KEY"), "  \n");
        assertNull(PasswordCipher.readMasterKey(secretsDir));
    }

    @Test
    void testEncryptDecrypt_shouldReadMountedKeyFiles_whenTheMasterKeyUnwrapsThem(@TempDir Path secretsDir)
            throws IOException {
        Files.writeString(secretsDir.resolve("KS_DEFAULT_KEY"), WRAPPED_KEY + "\n");
        Files.writeString(secretsDir.resolve("KS_LEGACY_KEY"), WRAPPED_KEY);
        Files.writeString(secretsDir.resolve("SYM_KEY"), MASTER_KEY + "\n");
        PasswordCipher mounted = new PasswordCipher(PasswordCipher.readKeyMaterial(secretsDir),
                PasswordCipher.readMasterKey(secretsDir));

        for (Stored stored : HISTORICAL_VALUES) {
            assertEquals(stored.plain(), mounted.decrypt(stored.envelope()), stored.envelope());
        }
        for (Stored stored : DEFAULT_SETTINGS_VALUES) {
            assertEquals(stored.envelope(), mounted.encrypt(stored.plain()));
        }
    }

    /** The way the {@code KS_*} files and the {@code {iv}} group hold their bytes: base64 of the text. */
    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static SecretKey masterKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(MASTER_KEY), "AES");
    }

    private static String aliasOf(String envelope) {
        return envelope.split("\\}")[2].substring(1);
    }
}
