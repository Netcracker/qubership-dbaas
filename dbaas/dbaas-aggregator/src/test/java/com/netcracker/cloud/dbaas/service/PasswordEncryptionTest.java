package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseUser;
import com.netcracker.cloud.dbaas.exceptions.DecryptException;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PasswordEncryptionTest {

    static final String PLAIN_VALUE = "this is plain value";
    static final String ENCRYPTED_VALUE = "this is encrypted value";
    private static final String MISSING_KEY_FAILURE = "No encryption key is configured for alias DEFAULT_KEY";

    PasswordCipher passwordCipher = mock(PasswordCipher.class);
    PasswordEncryption passwordEncryption = new PasswordEncryption(passwordCipher);

    @Test
    void testEncrypt() {
        Database database = new Database();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", PLAIN_VALUE);
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        database.setDatabaseRegistry(Arrays.asList(new DatabaseRegistry()));
        when(passwordCipher.encrypt(anyString())).thenReturn(ENCRYPTED_VALUE);
        passwordEncryption.encryptPassword(database);
        assertEquals(ENCRYPTED_VALUE, ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString()).get("encryptedPassword"));
    }

    @Test
    void testEncryptUserPassword() {
        DatabaseUser user = new DatabaseUser();
        user.setDatabase(new Database());
        user.setConnectionProperties(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", PLAIN_VALUE);
            put(ROLE, Role.ADMIN.toString());
        }});
        when(passwordCipher.encrypt(anyString())).thenReturn(ENCRYPTED_VALUE);
        passwordEncryption.encryptUserPassword(user);
        assertEquals(ENCRYPTED_VALUE, user.getConnectionProperties().get("encryptedPassword"));
    }

    @Test
    void testEncryptRole() {
        Database database = new Database();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", PLAIN_VALUE);
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        database.setDatabaseRegistry(Arrays.asList(new DatabaseRegistry()));
        when(passwordCipher.encrypt(anyString())).thenReturn(ENCRYPTED_VALUE);
        passwordEncryption.encryptPassword(database, Role.ADMIN.toString());
        assertEquals(ENCRYPTED_VALUE, ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN).get().get("encryptedPassword"));
    }

    @Test
    void testDecrypt() {
        Database database = new Database();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("encryptedPassword", ENCRYPTED_VALUE);
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        database.setDatabaseRegistry(Arrays.asList(new DatabaseRegistry()));
        when(passwordCipher.decrypt(ENCRYPTED_VALUE)).thenReturn(PLAIN_VALUE);
        passwordEncryption.decryptPassword(database);

        assertEquals(PLAIN_VALUE, ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString()).get("password"));
    }

    @Test
    void testDecryptUserPasswors() {
        DatabaseUser user = new DatabaseUser();
        user.setConnectionProperties(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("encryptedPassword", ENCRYPTED_VALUE);
            put(ROLE, Role.ADMIN.toString());
        }});
        when(passwordCipher.decrypt(ENCRYPTED_VALUE)).thenReturn(PLAIN_VALUE);
        passwordEncryption.decryptUserPassword(user);

        assertEquals(PLAIN_VALUE, user.getConnectionProperties().get("password"));
    }

    @Test
    void testUpdate() {
        Database database = new Database();
        database.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", ENCRYPTED_VALUE);
            put(ROLE, Role.ADMIN.toString());
        }}));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        PasswordEncryption spyPasswordEncryption = spy(passwordEncryption);
        doReturn(true).when(spyPasswordEncryption).decryptPassword(any());
        doNothing().when(spyPasswordEncryption).encryptPassword(any(Database.class));
        spyPasswordEncryption.updatePassword(database, "new password");
        assertEquals("new password", ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString()).get("password"));
    }

    @Test
    void testDeletePassword_shouldRemoveEncryptedValueWithoutWritingPlaintext() {
        Database database = databaseWith(Map.of(ROLE, Role.ADMIN.toString(),
                "username", "username", "encryptedPassword", ENCRYPTED_VALUE));

        passwordEncryption.deletePassword(database);

        Map<String, Object> properties = ConnectionPropertiesUtils
                .getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString());
        assertFalse(properties.containsKey("encryptedPassword"));
        assertFalse(properties.containsKey(PasswordEncryption.PASSWORD_FIELD));
        verifyNoInteractions(passwordCipher);
    }

    @Test
    void testDeletePasswordByRole_shouldRemoveEncryptedValueOfThatRoleOnly() {
        Database database = databaseWith(Map.of(ROLE, Role.ADMIN.toString(), "encryptedPassword", ENCRYPTED_VALUE),
                Map.of(ROLE, "reader", "encryptedPassword", "another encrypted value"));

        passwordEncryption.deletePassword(database, Role.ADMIN.toString());

        assertFalse(adminProperties(database).containsKey("encryptedPassword"));
        assertTrue(ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), "reader")
                .containsKey("encryptedPassword"));
        verifyNoInteractions(passwordCipher);
    }

    @Test
    void testEncryptPassword_shouldReplaceAdapterPasswordWithEncryptedValue() {
        PhysicalDatabaseRegistryRequestV3 registryRequest = new PhysicalDatabaseRegistryRequestV3();
        registryRequest.setHttpBasicCredentials(new HttpBasicCredentials("adapter-user", PLAIN_VALUE));
        when(passwordCipher.encrypt(PLAIN_VALUE)).thenReturn(ENCRYPTED_VALUE);

        passwordEncryption.encryptPassword(registryRequest);

        assertEquals(ENCRYPTED_VALUE, registryRequest.getHttpBasicCredentials().getPassword());
    }

    @Test
    void testGetDecryptedPasswordForBackup_shouldReturnPassword_whenTheValueDecrypts() {
        Database database = databaseWith(Map.of(ROLE, Role.ADMIN.toString(), "encryptedPassword", ENCRYPTED_VALUE));
        when(passwordCipher.decrypt(ENCRYPTED_VALUE)).thenReturn(PLAIN_VALUE);

        assertEquals(PLAIN_VALUE, passwordEncryption.getDecryptedPasswordForBackup(database, Role.ADMIN.toString()));
    }

    @Test
    void testGetDecryptedPasswordForBackup_shouldFailWithIllegalArgument_whenEncryptedPasswordIsAbsent() {
        Database database = databaseWith(Map.of(ROLE, Role.ADMIN.toString(), "username", "username"));
        // The cipher is the one that fails on an absent value. PasswordCipherTest pins that it fails with
        // NullPointerException, which is what makes this branch report a corrupt record instead of a bad key.
        when(passwordCipher.decrypt(null)).thenThrow(new NullPointerException());

        assertThrows(IllegalArgumentException.class,
                () -> passwordEncryption.getDecryptedPasswordForBackup(database, Role.ADMIN.toString()));
    }

    @Test
    void testGetDecryptedPasswordForBackup_shouldFailWithIllegalArgument_whenEncryptedPasswordIsNotText() {
        Database database = databaseWith(Map.of(ROLE, Role.ADMIN.toString(), "encryptedPassword", 42));

        assertThrows(IllegalArgumentException.class,
                () -> passwordEncryption.getDecryptedPasswordForBackup(database, Role.ADMIN.toString()));
        verifyNoInteractions(passwordCipher);
    }

    @Test
    void testGetDecryptedPasswordForBackup_shouldFailWithDecryptException_whenCipherCannotDecrypt() {
        Database database = databaseWith(Map.of(ROLE, Role.ADMIN.toString(), "encryptedPassword", ENCRYPTED_VALUE));
        when(passwordCipher.decrypt(ENCRYPTED_VALUE)).thenThrow(new IllegalStateException(MISSING_KEY_FAILURE));

        DecryptException thrown = assertThrows(DecryptException.class,
                () -> passwordEncryption.getDecryptedPasswordForBackup(database, Role.ADMIN.toString()));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals(MISSING_KEY_FAILURE, thrown.getCause().getMessage());
    }

    @SafeVarargs
    private static Database databaseWith(Map<String, Object>... connectionProperties) {
        List<Map<String, Object>> properties = new ArrayList<>();
        for (Map<String, Object> property : connectionProperties) {
            properties.add(new HashMap<>(property));
        }
        return new DatabaseBuilder().connectionProperties(properties).build();
    }

    private static Map<String, Object> adminProperties(Database database) {
        return ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(),
                Role.ADMIN.toString());
    }
}
