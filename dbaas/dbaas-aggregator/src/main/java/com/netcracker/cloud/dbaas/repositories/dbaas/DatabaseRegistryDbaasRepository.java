package com.netcracker.cloud.dbaas.repositories.dbaas;

import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseKind;
import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseType;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    List<DatabaseRegistry> findAllDatabasesByFilter(List<String> namespaces, List<String> microserviceName,
                                                    List<DatabaseType> databaseType, List<DatabaseKind> databaseKind);
}
