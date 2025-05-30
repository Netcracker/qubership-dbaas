package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DbState;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DatabaseDbaasRepository {

    List<Database> findInternalDatabaseByNamespace(String namespace);

    List<Database> findInternalDatabasesByNamespaceAndType(String namespace, String type);

    List<Database> findDatabasesByAdapterIdAndType(String adapterId, String type, boolean isUseCache);

    List<Database> findByDbState_StateAndDbState_PodName(DbState.DatabaseStateStatus state, String podName);

    List<Database> findByDbState(DbState.DatabaseStateStatus state);

    void deleteById(UUID databaseId);

    Optional<Database> findById(UUID id);

    List<Database> findExternalDatabasesByNamespace(String namespace);

    List<Database> findAnyLogDbTypeByNamespace(String namespace);

    void deleteAll(List<Database> databaseList);

    void reloadH2Cache();

    void reloadH2Cache(UUID databaseId);

    Object getMutex();

    Optional<Database> findByNameAndAdapterId(String databaseName, String adapterId);

    long countByNamespaces(Set<String> namespaces);

    List<Database> findByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit);
}
