package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.Secret;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import org.qubership.cloud.dbaas.dto.role.Role;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PasswordEncryptionTest {

    EncryptionServiceProvider encryptionServiceProvider = mock(EncryptionServiceProvider.class);
    DataEncryption dataEncryption = mock(DataEncryption.class);

    @Test
    public void testEncrypt() {
        Database database = new Database();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", "this is plain value");
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        database.setDatabaseRegistry(Arrays.asList(new DatabaseRegistry()));
        PasswordEncryption passwordEncryption = new PasswordEncryption(encryptionServiceProvider);
        when(encryptionServiceProvider.getEncryptionService()).thenReturn(dataEncryption);
        when(dataEncryption.encrypt(any(Secret.class))).thenReturn("this is encrypted value");
        passwordEncryption.encryptPassword(database);
        assertEquals("this is encrypted value", ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString()).get("encryptedPassword"));
    }

    @Test
    public void testEncryptUserPassword() {
        DatabaseUser user = new DatabaseUser();
        user.setDatabase(new Database());
        user.setConnectionProperties(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", "this is plain value");
            put(ROLE, Role.ADMIN.toString());
        }});
        PasswordEncryption passwordEncryption = new PasswordEncryption(encryptionServiceProvider);
        when(encryptionServiceProvider.getEncryptionService()).thenReturn(dataEncryption);
        when(dataEncryption.encrypt(any(Secret.class))).thenReturn("this is encrypted value");
        passwordEncryption.encryptUserPassword(user);
        assertEquals("this is encrypted value", user.getConnectionProperties().get("encryptedPassword"));
    }

    @Test
    public void testEncryptRole() {
        Database database = new Database();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", "this is plain value");
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        database.setDatabaseRegistry(Arrays.asList(new DatabaseRegistry()));
        PasswordEncryption passwordEncryption = new PasswordEncryption(encryptionServiceProvider);
        when(encryptionServiceProvider.getEncryptionService()).thenReturn(dataEncryption);
        when(dataEncryption.encrypt(any(Secret.class))).thenReturn("this is encrypted value");
        passwordEncryption.encryptPassword(database, Role.ADMIN.toString());
        assertEquals("this is encrypted value", ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN).get().get("encryptedPassword"));
    }

    @Test
    public void testDecrypt() {
        Database database = new Database();
        List<Map<String, Object>> connectionList = Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("encryptedPassword", "this is encrypted value");
            put(ROLE, Role.ADMIN.toString());
        }});
        database.setConnectionProperties(connectionList);
        database.setDatabaseRegistry(Arrays.asList(new DatabaseRegistry()));
        PasswordEncryption passwordEncryption = new PasswordEncryption(encryptionServiceProvider);
        Secret secret = new Secret("this is plain value");
        when(encryptionServiceProvider.getEncryptionService("this is encrypted value")).thenReturn(dataEncryption);
        when(dataEncryption.decrypt("this is encrypted value")).thenReturn(secret);
        passwordEncryption.decryptPassword(database);

        assertEquals("this is plain value", ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString()).get("password"));
    }

    @Test
    public void testDecryptUserPasswors() {
        DatabaseUser user = new DatabaseUser();
        user.setConnectionProperties(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("encryptedPassword", "this is encrypted value");
            put(ROLE, Role.ADMIN.toString());
        }});
        PasswordEncryption passwordEncryption = new PasswordEncryption(encryptionServiceProvider);
        Secret secret = new Secret("this is plain value");
        when(encryptionServiceProvider.getEncryptionService("this is encrypted value")).thenReturn(dataEncryption);
        when(dataEncryption.decrypt("this is encrypted value")).thenReturn(secret);
        passwordEncryption.decryptUserPassword(user);

        assertEquals("this is plain value", user.getConnectionProperties().get("password"));
    }

    @Test
    public void testUpdate() {
        Database database = new Database();
        database.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put("authDbName", "test-encrypt");
            put("password", "this is encrypted value");
            put(ROLE, Role.ADMIN.toString());
        }}));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        PasswordEncryption passwordEncryption = spy(new PasswordEncryption(encryptionServiceProvider));
        doReturn(true).when(passwordEncryption).decryptPassword(any());
        doNothing().when(passwordEncryption).encryptPassword(any(Database.class));
        passwordEncryption.updatePassword(database, "new password");
        assertEquals("new password", ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), Role.ADMIN.toString()).get("password"));
    }
}
