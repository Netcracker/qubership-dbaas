package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.ConnectionDescription;
import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;
import org.qubership.cloud.dbaas.dto.Secret;
import org.qubership.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.exceptions.RecordIsCorruptedException;
import org.qubership.cloud.encryption.cipher.exception.DecryptException;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.MapUtils;

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.dto.ConnectionDescription.FieldTypeEnum.PASSWORD;

@Slf4j
@Data
public class PasswordEncryption {
    public static final String ENCRYPTED_PASSWORD_FIELD = "encryptedPassword";
    public static final String ENCRYPTED_SECRET_FIELD = "encryptedSecret";
    public static final String PASSWORD_FIELD = "password";

    public PasswordEncryption(@NonNull EncryptionServiceProvider encryptionServiceProvider) {
        this.encryptionServiceProvider = encryptionServiceProvider;
        this.objectMapper = new ObjectMapper();
    }

    @NonNull
    private EncryptionServiceProvider encryptionServiceProvider;


    private ObjectMapper objectMapper;

    public void encryptPassword(Database database, String role) {
        List<Map<String, Object>> connectionProperties = database.getConnectionProperties().stream().map(HashMap::new).collect(Collectors.toList());
        Map<String, Object> cp = ConnectionPropertiesUtils.getConnectionProperties(connectionProperties, role);
        ConnectionDescription connectionDescription = database.getConnectionDescription() == null ?
                ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : database.getConnectionDescription();

        connectionDescription.getFields()
                .entrySet()
                .stream()
                .filter(field -> PASSWORD == field.getValue().getType() &&
                        !MapUtils.isEmpty(cp) &&
                        cp.containsKey(field.getKey()))
                .forEach(field -> {
                    String parameter = field.getKey();
                    Secret secret = new Secret((String) cp.get(parameter));
                    cp.put(encryptedFieldName(parameter), encrypt(secret));
                    cp.remove(parameter);
                });
        database.setConnectionProperties(connectionProperties);

    }

    public void encryptPassword(Database database) {
        List<Map<String, Object>> connectionProperties = database.getConnectionProperties().stream().map(HashMap::new).collect(Collectors.toList());
        ConnectionDescription connectionDescription = database.getConnectionDescription() == null ?
                ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : database.getConnectionDescription();
        connectionProperties.forEach(cp ->
                connectionDescription.getFields()
                        .entrySet()
                        .stream()
                        .filter(field -> PASSWORD == field.getValue().getType() &&
                                !MapUtils.isEmpty(cp) &&
                                cp.containsKey(field.getKey()))
                        .forEach(field -> {
                            String parameter = field.getKey();
                            Secret secret = new Secret((String) cp.get(parameter));
                            cp.put(encryptedFieldName(parameter), encrypt(secret));
                            cp.remove(parameter);
                        }));
        database.setConnectionProperties(connectionProperties);
    }

    public void encryptUserPassword(DatabaseUser user) {
        Map<String, Object> cp = new HashMap<String, Object>(user.getConnectionProperties());
        ConnectionDescription connectionDescription = user.getConnectionDescription() == null ?
                ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : user.getConnectionDescription();
        connectionDescription.getFields()
                .entrySet()
                .stream()
                .filter(field -> PASSWORD == field.getValue().getType() && cp.containsKey(field.getKey()))
                .forEach(field -> {
                    String parameter = field.getKey();
                    Secret secret = new Secret((String) cp.get(parameter));
                    cp.put(encryptedFieldName(parameter), encrypt(secret));
                    cp.remove(parameter);
                });
        user.setConnectionProperties(cp);
    }

    public void updatePassword(Database database, String newPassword) {
        updatePassword(database, Optional.empty(), newPassword);
    }

    public void updatePassword(Database database, Optional<String> username, String newPassword) {
        decryptPassword(database);

        ConnectionDescription connectionDescription = Optional.ofNullable(database.getConnectionDescription())
                .orElse(ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION);

        database.getConnectionProperties().stream()
                .filter(cp -> username.isEmpty() || Objects.equals(cp.get("username"), username.get())).findFirst()
                .ifPresentOrElse(cp -> connectionDescription
                                .getFields()
                                .entrySet()
                                .stream()
                                .filter(field -> PASSWORD == field.getValue().getType())
                                .forEach(field -> {
                                    String parameter = field.getKey();
                                    if (cp.containsKey(parameter)) {
                                        cp.put(parameter, newPassword);
                                    }
                                })
                        , () -> {
                            throw new IllegalArgumentException(String.format("Invalid username: %s provided on attempt to change password", username));
                        });

        encryptPassword(database);
    }

    public boolean decryptPassword(Database database) {
        ConnectionDescription connectionDescription = database.getConnectionDescription() == null
                ? ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : database.getConnectionDescription();
        List<Map<String, Object>> connectionProperties = database.getConnectionProperties().stream().map(HashMap::new).collect(Collectors.toList());
        if (connectionProperties == null) {
            throw new RecordIsCorruptedException(String.format("Record in DbaaS of database with id {} is corrupted", database.getId()));
        }
        List<Boolean> decrypts = connectionProperties.stream().map(cp -> connectionDescription
                .getFields()
                .entrySet()
                .stream()
                .filter(field -> PASSWORD == field.getValue().getType())
                .map(field -> {
                    String parameter = field.getKey();
                    if (cp.containsKey(encryptedFieldName(parameter))) {
                        cp.put(parameter, decrypt((String) cp.get(encryptedFieldName(parameter))));
                        cp.remove(encryptedFieldName(parameter));
                        return true;
                    } else {
                        return false;
                    }
                }).filter(val -> !val)
                .findFirst().orElse(true)).collect(Collectors.toList());
        database.setConnectionProperties(connectionProperties);
        return !decrypts.contains(false);
    }

    public boolean decryptUserPassword(DatabaseUser user) {
        ConnectionDescription connectionDescription = user.getConnectionDescription() == null
                ? ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : user.getConnectionDescription();
        if (user.getConnectionProperties() == null) {
            throw new RecordIsCorruptedException(String.format("Can't decrypt password because connection properties is absent. Database id %s",
                    user.getDatabase().getId()));
        }
        Map<String, Object> cp = new HashMap<>(user.getConnectionProperties());

        boolean decrypt = connectionDescription.getFields()
                .entrySet()
                .stream()
                .filter(field -> PASSWORD == field.getValue().getType())
                .map(field -> {
                    String parameter = field.getKey();
                    if (cp.containsKey(encryptedFieldName(parameter))) {
                        cp.put(parameter, decrypt((String) cp.get(encryptedFieldName(parameter))));
                        cp.remove(encryptedFieldName(parameter));
                        return true;
                    } else
                        return false;
                }).filter(val -> !val)
                .findFirst().orElse(true);
        user.setConnectionProperties(cp);
        return decrypt;
    }

    public void deletePassword(Database database, String role) {
        ConnectionDescription connectionDescription = database.getConnectionDescription() == null
                ? ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : database.getConnectionDescription();
        Map<String, Object> cp = ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), role);
        connectionDescription
                .getFields()
                .entrySet()
                .stream()
                .filter(field -> PASSWORD == field.getValue().getType())
                .forEach(field -> {
                    String parameter = field.getKey();
                    if (cp != null && cp.containsKey(encryptedFieldName(parameter))) {
                        String encryptedData = (String) cp.get(encryptedFieldName(parameter));
                        DataEncryption encryption = encryptionServiceProvider.getEncryptionService(encryptedData);
                        log.info("Delete encrypted data for database {}", database.getName());
                        encryption.remove(encryptedData);
                        cp.remove(encryptedFieldName(parameter));
                    }
                });

    }

    public void deletePassword(Database database) {
        ConnectionDescription connectionDescription = database.getConnectionDescription() == null
                ? ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION : database.getConnectionDescription();
        List<Map<String, Object>> connectionProperties = database.getConnectionProperties();
        deletePasswordFromConnectionProperties(database.getName(), connectionDescription, connectionProperties);

    }

    private void deletePasswordFromConnectionProperties(String name, ConnectionDescription connectionDescription, List<Map<String, Object>> connectionProperties) {
        connectionProperties.forEach(cp ->
                connectionDescription
                        .getFields()
                        .entrySet()
                        .stream()
                        .filter(field -> PASSWORD == field.getValue().getType())
                        .forEach(field -> {
                            String parameter = field.getKey();
                            if (cp.containsKey(encryptedFieldName(parameter))) {
                                String encryptedData = (String) cp.get(encryptedFieldName(parameter));
                                DataEncryption encryption = encryptionServiceProvider.getEncryptionService(encryptedData);
                                log.info("Delete encrypted data for database {}", name);
                                encryption.remove(encryptedData);
                                cp.remove(encryptedFieldName(parameter));
                            }
                        }));
    }

    public void deletePassword(PhysicalDatabase database) {
        HttpBasicCredentials adapterHttpBasicCredentials = database.getAdapter().getHttpBasicCredentials();
        DataEncryption encryption = encryptionServiceProvider.getEncryptionService(adapterHttpBasicCredentials.getPassword());
        log.info("Delete encrypted data for physical database {}", database.getPhysicalDatabaseIdentifier());
        encryption.remove(adapterHttpBasicCredentials.getPassword());
    }

    public void encryptPassword(PhysicalDatabaseRegistryRequestV3 registryRequest) {
        HttpBasicCredentials adapterHttpBasicCredentials = registryRequest.getHttpBasicCredentials();
        Secret secret = new Secret(adapterHttpBasicCredentials.getPassword());
        String encryptedPassword = encrypt(secret);
        adapterHttpBasicCredentials.setPassword(encryptedPassword);
    }


    public String getDecryptedPasswordForBackup(Database database, String role) {
        Map<String, Object> connectionProperties = ConnectionPropertiesUtils.getConnectionProperties(database.getConnectionProperties(), role);

        String encryptedPassword;
        try {
            encryptedPassword = (String) connectionProperties.get(ENCRYPTED_PASSWORD_FIELD);
            return decrypt(encryptedPassword);
        } catch (NullPointerException | ClassCastException e) {
            log.error("Got incorrect connection properties without valid password field, cannot decrypt password.", e);
            throw new IllegalArgumentException("Connection properties of database"
                    + database.getName() + " does not have field " + ENCRYPTED_PASSWORD_FIELD);
        } catch (Exception e) {
            log.error("An error occurred while getting decrypted password", e);
            throw new DecryptException("Error when decrypting password", e);
        }
    }

    public String decrypt(String encryptedPassword) {
        return encryptionServiceProvider.getEncryptionService(encryptedPassword)
                .decrypt(encryptedPassword)
                .getData();
    }

    private String encrypt(Secret secret) {
        return encryptionServiceProvider.getEncryptionService().encrypt(secret);
    }

    private String encryptedFieldName(String word) {
        return "encrypted" + Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
