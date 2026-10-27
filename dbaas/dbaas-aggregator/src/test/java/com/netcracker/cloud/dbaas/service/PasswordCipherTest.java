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
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasswordCipherTest {

    private static final String KEY = base64("0123456789abcdef");
    private static final String MASTER_KEY = base64("fedcba9876543210");
    private static final String OTHER_KEY = base64("0123456789ABCDEF");
    /** {@link #KEY} encrypted with {@link #MASTER_KEY}, the way a key is stored when SYM_KEY is configured. */
    private static final String WRAPPED_KEY = "PVuaCIiaKQhblgFCbVMTTpet090Hk8duEXLYgdGOcwc=";

    private static final PasswordCipher CIPHER =
            new PasswordCipher(Map.of("DEFAULT_KEY", KEY, "LEGACY_KEY", KEY), null);

    /** A plaintext and the encrypted value DBaaS keeps for it. */
    private record CapturedValue(String plain, String envelope) {
    }

    /**
     * Envelopes captured from the encryption library earlier releases shipped, written as AES under DEFAULT_KEY. A
     * payload follows from {@link #KEY} alone, the mode being deterministic AES/ECB/PKCS5Padding with no IV, so
     * {@code printf 'x' | openssl enc -aes-128-ecb -K <key text in hex> -nosalt -a -A} builds any of them.
     */
    private static final CapturedValue[] WRITTEN_VALUES = {
            new CapturedValue("simplePassword123",
                    "{v2c}{AES}{DEFAULT_KEY}{8WI3xsBb6DbPUaj/G6hkWsMQxPuKn/LBXajeJdXIym0=}"),
            new CapturedValue("", "{v2c}{AES}{DEFAULT_KEY}{N3Ii4GGpJMWRzZwn6hY+1A==}"),
            new CapturedValue("p\u00e4ssw\u00f6rd-unicode",
                    "{v2c}{AES}{DEFAULT_KEY}{q2fl9s77QwHhY317Py6RaWXxqYS7jdTemGGpiACNgmc=}"),
            new CapturedValue("sp ec!@#$%^&*()_+-={}[]|:;\"'<>,.?/~`",
                    "{v2c}{AES}{DEFAULT_KEY}{kHYJFljhuCAPY7q9Mk+a8Hz+otValSYgP7sfscYABhQPn5KKb7Eh2IVor6jhEltm}"),
            new CapturedValue("L".repeat(64),
                    "{v2c}{AES}{DEFAULT_KEY}{SMgigX0D+WDhFikmRE2hbUjIIoF9A/lg4RYpJkRNoW1IyCKBfQP5YOEWKS"
                            + "ZETaFtSMgigX0D+WDhFikmRE2hbTdyIuBhqSTFkc2cJ+oWPtQ=}"),
    };

    private static final CapturedValue SIMPLE_VALUE = WRITTEN_VALUES[0];

    /** Stored under the alias of a key that a later key replaced as the default. */
    private static final CapturedValue ROTATED_ALIAS = new CapturedValue("rotatedKeyPassword",
            "{v2c}{AES}{LEGACY_KEY}{/1+RLnHj9moxXstfYJ15AVJcY8w1AcZRCQ3G6HTODJs=}");

    private static final CapturedValue CBC_VALUE = new CapturedValue("legacyCbcPassword",
            "{v2c}{AES/CBC/PKCS5Padding}{DEFAULT_KEY}{" + base64("abcdefghijklmnop") + "}"
                    + "{u5Vg9wYvYBDqenukSrYF8Q0wLjBDai4IRx0Jmr3kDww=}");

    private static final CapturedValue CBC_EMPTY_VALUE = new CapturedValue("",
            "{v2c}{AES/CBC/PKCS5Padding}{DEFAULT_KEY}{" + base64("abcdefghijklmnop") + "}"
                    + "{gdhetxVyVGOIr0wMPWN5FA==}");

    /** What a reader has to accept: what this class writes, plus shapes no release wrote but the format allows. */
    private static final CapturedValue[] READABLE_VALUES = Stream.concat(Arrays.stream(WRITTEN_VALUES),
                    Stream.of(CBC_VALUE, CBC_EMPTY_VALUE, ROTATED_ALIAS))
            .toArray(CapturedValue[]::new);

    /** {@link #SIMPLE_VALUE} with a byte added to the payload, so it is no longer a whole number of AES blocks. */
    private static final String CORRUPTED_VALUE =
            "{v2c}{AES}{DEFAULT_KEY}{8WI3xsBb6DbPUaj/G6hkWsMQxPuKn/LBXajeJdXIym0AAA=}";

    /** {@link #SIMPLE_VALUE} plus a redundant '=' in the payload, which a strict base64 decoder refuses. */
    private static final String SLOPPY_PADDING_VALUE =
            "{v2c}{AES}{DEFAULT_KEY}{8WI3xsBb6DbPUaj/G6hkWsMQxPuKn/LBXajeJdXIym0==}";

    /** No test runs the earlier library, so a rollback reads our output only because these bytes are what it wrote. */
    @Test
    void testEncrypt_shouldWriteTheSameBytesAsEarlierReleases() {
        for (CapturedValue captured : WRITTEN_VALUES) {
            assertEquals(captured.envelope(), CIPHER.encrypt(captured.plain()), captured.plain());
        }
    }

    @Test
    void testDecrypt_shouldReadEveryValueTheFormatAllows() {
        for (CapturedValue captured : READABLE_VALUES) {
            assertEquals(captured.plain(), CIPHER.decrypt(captured.envelope()), captured.envelope());
        }
    }

    @Test
    void testDecrypt_shouldReadAValueThatTheStrictBase64DecoderRefuses() {
        // decrypt reports only GeneralSecurityException as DecryptException, so the strict JDK decoder would leak an
        // IllegalArgumentException to DBBackupsService instead of a credential it regenerates.
        assertEquals(SIMPLE_VALUE.plain(), CIPHER.decrypt(SLOPPY_PADDING_VALUE), SLOPPY_PADDING_VALUE);
    }

    @Test
    void testEncryptDecrypt_shouldUseTheSameBytes_whenKeyMaterialIsWrappedByTheMasterKey() {
        PasswordCipher wrapped = new PasswordCipher(Map.of("DEFAULT_KEY", WRAPPED_KEY, "LEGACY_KEY", WRAPPED_KEY),
                masterKey());
        for (CapturedValue captured : READABLE_VALUES) {
            assertEquals(captured.plain(), wrapped.decrypt(captured.envelope()), captured.envelope());
        }
        for (CapturedValue captured : WRITTEN_VALUES) {
            assertEquals(captured.envelope(), wrapped.encrypt(captured.plain()), captured.plain());
        }
    }

    @Test
    void testEncrypt_shouldKeepWritingTheDefaultEnvelope_whenAnOlderAliasIsStillConfigured() {
        assertTrue(CIPHER.encrypt("rotatedKeyPassword").startsWith("{v2c}{AES}{DEFAULT_KEY}{"));
        assertTrue(ROTATED_ALIAS.envelope().startsWith("{v2c}{AES}{LEGACY_KEY}{"));
    }

    @Test
    void testIsEncrypted_shouldRecogniseEveryStoredFormatOnly() {
        for (CapturedValue captured : READABLE_VALUES) {
            assertTrue(CIPHER.isEncrypted(captured.envelope()), captured.envelope());
            assertFalse(CIPHER.isEncrypted(captured.plain()), captured.envelope());
        }
    }

    @Test
    void testIsEncrypted_shouldRecogniseAValueWithATrailingLineBreak() {
        assertTrue(CIPHER.isEncrypted(SIMPLE_VALUE.envelope() + "\n"));
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
        // A DecryptException here would make a backup regenerate the password instead of reporting the corrupt record.
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
        assertThrows(DecryptException.class, () -> other.decrypt(SIMPLE_VALUE.envelope()));
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

        for (CapturedValue captured : READABLE_VALUES) {
            assertEquals(captured.plain(), mounted.decrypt(captured.envelope()), captured.envelope());
        }
        for (CapturedValue captured : WRITTEN_VALUES) {
            assertEquals(captured.envelope(), mounted.encrypt(captured.plain()), captured.plain());
        }
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static SecretKey masterKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(MASTER_KEY), "AES");
    }
}
