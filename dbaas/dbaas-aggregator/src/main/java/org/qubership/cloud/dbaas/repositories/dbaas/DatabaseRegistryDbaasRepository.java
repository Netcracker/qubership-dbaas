package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

public interface DatabaseRegistryDbaasRepository {
    List<DatabaseRegistry> findAnyLogDbRegistryTypeByNamespace(String namespace);

    void deleteById(UUID databaseRegistryId);

    List<DatabaseRegistry> findExternalDatabaseRegistryByNamespace(String namespace);

    Optional<DatabaseRegistry> getDatabaseByClassifierAndType(Map<String, Object> classifier, String type);

    @Nullable
    DatabaseRegistry getDatabaseByOldClassifierAndType(Map<String, Object> classifier, String type);

    List<DatabaseRegistry> findInternalDatabaseRegistryByNamespace(String namespace);

    void delete(DatabaseRegistry database);

    void deleteOnlyTransactionalDatabaseRegistries(List<DatabaseRegistry> database);

    void deleteExternalDatabases(List<Database> databases, String namespace);

    List<DatabaseRegistry> findAnyLogDbTypeByNameAndOptionalParams(String name, @Nullable String namespace);

    DatabaseRegistry saveExternalDatabase(DatabaseRegistry databaseRegistry);

    DatabaseRegistry saveInternalDatabase(DatabaseRegistry databaseRegistry);

    DatabaseRegistry saveAnyTypeLogDb(DatabaseRegistry databaseRegistry);

    Optional<DatabaseRegistry> findDatabaseRegistryById(UUID id);

    List<DatabaseRegistry> findAllInternalDatabases();

    List<DatabaseRegistry> findAllDatabaseRegistersAnyLogType();

    List<DatabaseRegistry> findAllDatabasesAnyLogTypeFromCache();

    List<DatabaseRegistry> saveAll(List<DatabaseRegistry> databaseList);


    List<DatabaseRegistry> findDatabasesByMicroserviceNameAndNamespace(String microserviceName, String namespace);

    void reloadDatabaseRegistryH2Cache(UUID databaseRegistryId);

    void deleteOnlyTransactionalDatabaseRegistries(String namespace);

    List<DatabaseRegistry> findAllVersionedDatabaseRegistries(String namespace);

    Object getMutex();

    List<DatabaseRegistry> findAllTenantDatabasesInNamespace(String namespace);

    List<DatabaseRegistry> findAllTransactionalDatabaseRegistries(String namespace);

}
