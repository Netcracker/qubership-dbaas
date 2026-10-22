package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseUser;
import com.netcracker.cloud.dbaas.dto.role.Role;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PasswordEncryptionTest {

    static final String PLAIN_VALUE = "this is plain value";
    static final String ENCRYPTED_VALUE = "this is encrypted value";

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
}

