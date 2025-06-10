package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersRequest;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersResponse;
import org.qubership.cloud.dbaas.dto.userrestore.SuccessfullRestore;
import org.qubership.cloud.dbaas.dto.userrestore.UnsuccessfulRestore;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserRequest;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserResponse;
import org.qubership.cloud.dbaas.dto.v3.UserOperationRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.exceptions.DbNotFoundException;
import org.qubership.cloud.dbaas.exceptions.NotExistingConnectionPropertiesException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseUserRepository;
import org.qubership.cloud.dbaas.utils.DbaasBackupUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang.StringUtils;

import java.util.*;

@ApplicationScoped
@Slf4j
public class UserService {
    private final PasswordEncryption encryption;

    private final DatabaseUserRepository databaseUserRepository;

    private final PhysicalDatabasesService physicalDatabasesService;

    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Setter
    private DBaaService dBaaService;

    public UserService(PasswordEncryption encryption,
                       DatabaseUserRepository databaseUserRepository,
                       PhysicalDatabasesService physicalDatabasesService,
                       DatabaseRegistryDbaasRepository databaseDbaasRepository) {
        this.encryption = encryption;
        this.databaseUserRepository = databaseUserRepository;
        this.physicalDatabasesService = physicalDatabasesService;
        this.databaseRegistryDbaasRepository = databaseDbaasRepository;
    }


    public Optional<DatabaseUser> findUserByLogicalUserIdAndDatabaseId(String logicalUserId, Database database) {
        List<DatabaseUser> databaseUsers = databaseUserRepository.findByLogicalDatabaseId(database.getId());
        return databaseUsers.stream().filter(databaseUser ->
                databaseUser.getLogicalUserId() != null &&
                        databaseUser.getLogicalUserId().equals(logicalUserId)).findFirst();
    }

    public void decryptPassword(DatabaseUser user) {
        encryption.decryptUserPassword(user);
    }

    public GetOrCreateUserResponse createUser(GetOrCreateUserRequest request, Database db) {
        DatabaseUser preCreatedUser = new DatabaseUser(request, DatabaseUser.CreationMethod.ON_REQUEST, db);
        preCreatedUser.setStatus(DatabaseUser.Status.CREATING);
        databaseUserRepository.persist(preCreatedUser);
        String physicalDbId = StringUtils.isNotBlank(request.getPhysicalDbId()) ?
                request.getPhysicalDbId() :
                db.getPhysicalDatabaseId();
        DbaasAdapter adapter = physicalDatabasesService.getAdapterByPhysDbId(physicalDbId);

        if (request.getUserRole() == null) {
            request.setUserRole("admin");
        }

        EnsuredUser adapterResponse = adapter.createUser(db.getName(),
                null,
                request.getUserRole(),
                request.getUsernamePrefix());

        return saveNewDatabaseUserAndResources(adapterResponse, db, request, preCreatedUser);
    }

    @Transactional
    public RestoreUsersResponse restoreUsers(RestoreUsersRequest request) {
        Optional<DatabaseRegistry> databaseOptional = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(request.getClassifier(), request.getType());
        if (databaseOptional.isEmpty()) {
            log.error("Database with classifier={} is not found.", request.getClassifier());
            throw new DbNotFoundException(request.getType(), request.getClassifier(), Source.builder().pointer("").build());
        }
        DatabaseRegistry database = databaseOptional.get();

        DbaasAdapter adapter = physicalDatabasesService.getAdapterById(database.getAdapterId());

        encryption.decryptPassword(database.getDatabase());
        List<Map<String, Object>> connectionProperties;

        if (request.getRole() != null && !request.getRole().isEmpty()) {
            connectionProperties = database.getDatabase().getConnectionProperties().stream().filter(cp -> cp.get("role").equals(request.getRole())).toList();
            if (connectionProperties.isEmpty()) {
                throw new NotExistingConnectionPropertiesException(request.getRole());
            }
        } else {
            connectionProperties = database.getDatabase().getConnectionProperties();
        }

        RestoreUsersResponse response = new RestoreUsersResponse(new ArrayList<>(), new ArrayList<>());
        for (Map<String, Object> cp : connectionProperties) {
            try {
                adapter.ensureUser(
                    cp.get("username").toString(),
                    cp.get("password").toString(),
                    DbaasBackupUtils.getDatabaseName(database),
                    cp.get("role").toString()
                );
                response.getSuccessfully().add(new SuccessfullRestore(cp));
            } catch (WebApplicationException ex) {
                response.getUnsuccessfully().add(new UnsuccessfulRestore(cp, ex.getMessage()));
            }
        }
        return response;
    }

    private GetOrCreateUserResponse saveNewDatabaseUserAndResources(EnsuredUser responseBody, Database database,
                                                                    GetOrCreateUserRequest request, DatabaseUser user) {
        user.setConnectionProperties(responseBody.getConnectionProperties());
        encryption.encryptUserPassword(user);
        user.setStatus(DatabaseUser.Status.CREATED);
        databaseUserRepository.persist(user);
        database.getResources().addAll(responseBody.getResources());
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().get(0));

        user.setConnectionProperties(responseBody.getConnectionProperties());
        dBaaService.getConnectionPropertiesService().addAdditionalPropToCP(user);
        return new GetOrCreateUserResponse(user.getUserId().toString(), user.getConnectionProperties());
    }

    public Optional<DatabaseUser> findUser(UserOperationRequest request) {
        if (StringUtils.isNotBlank(request.getUserId())) {
            return databaseUserRepository.findByIdOptional(UUID.fromString(request.getUserId()));
        }
        DatabaseRegistry databaseRegistry = dBaaService.findDatabaseByClassifierAndType(request.getClassifier(), request.getType(), true);
        if (databaseRegistry != null) {
            List<DatabaseUser> databaseUsers = databaseUserRepository.findByLogicalDatabaseId(databaseRegistry.getDatabase().getId());
            return databaseUsers.stream()
                    .filter(u -> u.getLogicalUserId().equals(request.getLogicalUserId())).findFirst();
        }
        log.error("Database with classifier={} is not found.", request.getClassifier());
        throw new DbNotFoundException(request.getType(),
                request.getClassifier(),
                Source.builder().pointer("").build());
    }

    public DatabaseUser rotatePassword(DatabaseUser user) {
        Database database = user.getDatabase();
        DbaasAdapter adapter = physicalDatabasesService.getAdapterByPhysDbId(database.getPhysicalDatabaseId());

        EnsuredUser ensureUser = adapter.ensureUser(
                user.getConnectionProperties().get("username").toString(),
                null,
                database.getName(),
                user.getUserRole());

        user.setConnectionProperties(ensureUser.getConnectionProperties());
        database.getResources().addAll(ensureUser.getResources());
        encryption.encryptUserPassword(user);
        databaseUserRepository.persist(user);
        databaseRegistryDbaasRepository.saveAnyTypeLogDb(database.getDatabaseRegistry().get(0)); // todo arvo DatabaseUser must specify on dotabaseRegistry
        encryption.decryptUserPassword(user);
        return user;
    }

    public boolean deleteUser(DatabaseUser user) {
        Database database = user.getDatabase();
        DbaasAdapter adapter = physicalDatabasesService.getAdapterByPhysDbId(database.getPhysicalDatabaseId());

        DbResource resource = database.getResources().stream()
                .filter(dbResource -> dbResource.getKind().equals("user") &&
                        dbResource.getName().equals(user.getConnectionProperties().get("username")))
                .findFirst()
                .orElse(null);
        if (resource == null) {
            log.error("Resources for user with 'userId'={} is not found", user.getUserId());
            return false;
        }
        boolean isUserDeleted = adapter.deleteUser(Collections.singletonList(resource));
        if (isUserDeleted) {
            databaseUserRepository.delete(user);
            database.getResources().remove(resource);
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(user.getDatabase().getDatabaseRegistry().get(0));
        }
        return isUserDeleted;
    }

    public void removeFromDbaasStorage(DatabaseUser databaseUser) {
        databaseUserRepository.deleteById(databaseUser.getUserId());
    }

    public void deleteDatabaseUsers(Database database) {
        List<DatabaseUser> users = databaseUserRepository.findByLogicalDatabaseId(database.getId());
        users.forEach(databaseUserRepository::delete);
    }

    public List<DatabaseUser> findUsersByDatabase(Database database) {
        return databaseUserRepository.findByLogicalDatabaseId(database.getId());
    }

}
