package com.netcracker.cloud.dbaas.dao.jpa;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import com.netcracker.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.netcracker.cloud.dbaas.config.ServicesConfig.DBAAS_REPOSITORIES_MUTEX;
import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

@Slf4j
@ApplicationScoped
public class DatabaseDbaasRepositoryImpl implements DatabaseDbaasRepository {

    private DatabasesRepository databasesRepository;
    private H2DatabaseRepository h2DatabaseRepository;
    private H2DatabaseRegistryRepository h2DatabaseRegistryRepository;

    public DatabaseDbaasRepositoryImpl(DatabasesRepository databasesRepository, H2DatabaseRepository h2DatabaseRepository,
                                       H2DatabaseRegistryRepository h2DatabaseRegistryRepository) {
        this.databasesRepository = databasesRepository;
        this.h2DatabaseRepository = h2DatabaseRepository;
        this.h2DatabaseRegistryRepository = h2DatabaseRegistryRepository;
    }

    public Object getMutex() {
        return DBAAS_REPOSITORIES_MUTEX;
    }

    @Override
    public List<Database> findAnyLogDbTypeByNamespace(String namespace) {
StructuredLog.debug(log, "Search all logical databases by namespace=", "namespace", namespace);
        List<Database> databaseList = doGet(() -> databasesRepository.findByNamespace(namespace), ex -> {
StructuredLog.debug(log, "Catch exception = while trying to find logical database by namespace in Postgre, go to h2 database", "exception", ex.getMessage());
            return h2DatabaseRepository.findByNamespace(namespace).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
        });
StructuredLog.debug(log, "Was found logical database in namespace=", "count", databaseList.size(), "namespace", namespace);
        return databaseList;
    }

    @Override
    public List<Database> findInternalDatabaseByNamespace(String namespace) {
StructuredLog.debug(log, "Search internal logical databases by namespace", "namespace", namespace);
        List<Database> databases = doGet(() -> databasesRepository.findByNamespace(namespace), ex -> {
            StructuredLog.debug(log, "Catch exception while falling back to h2 database",
                    "exception", ex.getMessage(), "context", "find internal logical database by namespace in Postgre");
            return h2DatabaseRepository.findByNamespace(namespace).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
        });
        List<Database> databaseList = databases.stream()
                .filter(database -> !database.isExternallyManageable())
                .collect(Collectors.toList());
StructuredLog.debug(log, "Was found internal logical databases in namespace=", "count", databaseList.size(), "namespace", namespace);
        return databaseList;
    }

    @Override
    public List<Database> findInternalDatabasesByNamespaceAndType(String namespace, String type) {
StructuredLog.debug(log, "Search internal logical database with type= in namespace=", "type", type, "namespace", namespace);
        List<Database> databases = doGet(() -> databasesRepository.findByNamespaceAndType(namespace, type), ex -> {
            StructuredLog.debug(log, "Catch exception = while trying to find logical database by namespace and type in Postgre, go to h2 database", "error", ex.getMessage());
            return h2DatabaseRepository.findByNamespaceAndType(namespace, type).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
        });
        List<Database> databasesList = databases.stream()
                .filter(database -> !database.isExternallyManageable())
                .collect(Collectors.toList());
StructuredLog.debug(log, "Was found logical databases in namespace= with type=", "count", databasesList.size(), "namespace", namespace, "type", type);
        return databasesList;
    }

    @Override
    public List<Database> findDatabasesByAdapterIdAndType(String phydbid, String type, boolean isUseCache) {
StructuredLog.debug(log, "Search logical database with adapterId= and type=", "phydbid", phydbid, "type", type);
        List<Database> databases = doGet(() -> databasesRepository.findByAdapterIdAndType(phydbid, type), ex -> {
            StructuredLog.debug(log, "Catch exception = while trying to find logical database by namespace and type in Postgres, go to h2 database", "error", ex.getMessage());
            if (!isUseCache) {
                throw new RuntimeException(ex);
            }
            return h2DatabaseRepository.findByAdapterIdAndType(phydbid, type).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
        });
        List<Database> databasesList = databases.stream()
                .filter(database -> !database.isExternallyManageable())
                .collect(Collectors.toList());
StructuredLog.debug(log, "Was found logical databases with adapterId= and type=", "count", databasesList.size(), "phydbid", phydbid, "type", type);
        return databasesList;
    }

    public List<Database> findByDbState_StateAndDbState_PodName(DbState.DatabaseStateStatus state, String podName) {
StructuredLog.debug(log, "Search logical databases with state= and podName=", "state", state, "podName", podName);
        List<Database> databaseList = doGet(() -> databasesRepository.findByDbState_DatabaseStateAndDbState_PodName(state, podName), ex -> {
            log.warn("Catch exception while trying to find logical database by state and podName in Postgresql, go to h2 database", ex);
            return h2DatabaseRepository.findByDbState_StateAndDbState_PodName(state, podName).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
        });
StructuredLog.debug(log, "Was found logical database with state= and podName=", "count", databaseList.size(), "state", state, "podName", podName);
        return databaseList;

    }

    public List<Database> findByDbState(DbState.DatabaseStateStatus state) {
StructuredLog.debug(log, "Search logical databases with state=", "state", state);
        List<Database> databaseList = doGet(() -> databasesRepository.findByDbState_DatabaseState(state), ex -> {
            log.warn("Catch exception while trying to find logical database by state and podName in Postgresql, go to h2 database", ex);
            return h2DatabaseRepository.findByDbState_DatabaseState(state).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
        });
StructuredLog.debug(log, "Was found logical database with state=", "count", databaseList.size(), "state", state);
        return databaseList;
    }


    public void deleteById(UUID databaseId) {
StructuredLog.debug(log, "delete database by id =", "databaseId", databaseId);
        synchronized (getMutex()) {
            databasesRepository.deleteById(databaseId);
            h2DatabaseRepository.deleteById(databaseId);
            h2DatabaseRepository.flush();
        }
StructuredLog.debug(log, "database with id = was deleted", "databaseId", databaseId);
    }


    @Override
    public Optional<Database> findById(UUID id) {
        return doGet(() -> databasesRepository.findByIdOptional(id), ex -> {
            StructuredLog.debug(log, "Catch exception = while trying to find logical database by id in Postgre, go to h2 database", "error", ex.getMessage());
            return h2DatabaseRepository.findByIdOptional(id).map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity);
        });
    }

    @Override
    public List<Database> findExternalDatabasesByNamespace(String namespace) {
StructuredLog.debug(log, "Search external logical database with namespace", "namespace", namespace);
        List<Database> databases = doGet(() -> databasesRepository.findByNamespace(namespace),
                ex -> {
                    StructuredLog.debug(log, "Catch exception = while trying to find external logical database in Postgre, go to h2 database", "error", ex.getMessage());
                    return h2DatabaseRepository.findByNamespace(namespace).stream().map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
                });
        List<Database> externalDatabases = databases.stream()
                .filter(Database::isExternallyManageable).collect(Collectors.toList());
StructuredLog.debug(log, "Was found external logical database with namespace", "count", externalDatabases.size(), "namespace", namespace);
        return externalDatabases;
    }


    @Override
    public void deleteAll(List<Database> databaseList) {
StructuredLog.info(log, "Deleting databases:", "databaseList", databaseList);
        databaseList.forEach(db -> databasesRepository.deleteById(db.getId()));
        databasesRepository.flush();
        synchronized (getMutex()) {
            databaseList.forEach(db -> h2DatabaseRepository.deleteById(db.getId()));
            h2DatabaseRepository.flush();
        }
    }

    @Override
    public Optional<Database> findByNameAndAdapterId(String name, String adapterId) {
        return doGet(() -> databasesRepository.findByNameAndAdapterId(name, adapterId), ex -> {
            StructuredLog.debug(log, "Catch exception = while trying to find logical database by id in Postgre, go to h2 database", "error", ex.getMessage());
            return h2DatabaseRepository.findByNameAndAdapterId(name, adapterId).map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity);
        });
    }

    @Override
    public long countByNamespaces(Set<String> namespaces) {
        return doGet(() -> databasesRepository.count("namespace in ?1", namespaces), ex -> {
            StructuredLog.debug(log, "Catch exception = while trying to count logical databases by namespaces in Postgres, go to h2 database", "error", ex.getMessage());
            return h2DatabaseRepository.count("namespace in ?1", namespaces);
        });
    }

    @Override
    public List<Database> findByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit) {
        return doGet(() -> databasesRepository.findByNamespacesWithOffsetBasedPagination(namespaces, offset, limit), ex -> {
            StructuredLog.debug(log, "Catch exception = while trying to find logical database by namespaces in Postgres, go to h2 database", "error", ex.getMessage());
            return h2DatabaseRepository.findByNamespacesWithOffsetBasedPagination(namespaces, offset, limit).stream()
                .map(com.netcracker.cloud.dbaas.entity.h2.Database::asPgEntity)
                .toList();
        });
    }

    public void reloadH2Cache() {
        log.debug("reload Database h2");
        List<Database> foundDatabases = databasesRepository.listAll();
        h2DatabaseRepository.deleteAll();
        h2DatabaseRepository.flush();
        h2DatabaseRepository.merge(foundDatabases.stream().map(Database::asH2Entity).toList());
        h2DatabaseRepository.flush();
    }

    public void reloadH2Cache(UUID databaseId) {
StructuredLog.debug(log, "reload in h2 database with id=", "databaseId", databaseId);
        Optional<Database> database = databasesRepository.findByIdOptional(databaseId);
        safeDeleteAndFlushDatabase(databaseId);
        database.ifPresent(value -> QuarkusTransaction.requiringNew().run(() -> {
StructuredLog.debug(log, "save in h2 database=", "value", value);
            h2DatabaseRepository.merge(value.asH2Entity());
        }));
    }

    private <T> T doGet(Callable<T> action, Function<Exception, T> rollback) {
        try {
            return action.call();
        } catch (Exception e) {
            synchronized (getMutex()) {
                return QuarkusTransaction.requiringNew().call(() -> rollback.apply(e));
            }
        }
    }

    private void safeDeleteAndFlushDatabase(UUID databaseId) {
        Optional<com.netcracker.cloud.dbaas.entity.h2.Database> database = h2DatabaseRepository.findByIdOptional(databaseId);
        if (database.isEmpty()) {
            return;
        }
        List<com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry> databaseRegistry = database.get().getDatabaseRegistry();
        for (DatabaseRegistry registry : databaseRegistry) {
            safeDeleteAndFlushDatabaseRegistry(registry.getId());
        }
    }

    @Transactional(REQUIRES_NEW)
    protected void safeDeleteAndFlushDatabaseRegistry(UUID databaseRegistryId) {
        Optional<com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry> databaseRegistryOptional = h2DatabaseRegistryRepository.findByIdOptional(databaseRegistryId);
        if (databaseRegistryOptional.isEmpty()) {
            return;
        }
        DatabaseRegistry databaseRegistry = databaseRegistryOptional.get();

        com.netcracker.cloud.dbaas.entity.h2.Database database = databaseRegistry.getDatabase();

        database.getDatabaseRegistry().remove(databaseRegistry);
        if (database.getDatabaseRegistry().isEmpty()) {
            h2DatabaseRepository.deleteById(database.getId());
        } else {
            h2DatabaseRepository.persist(database);
        }

StructuredLog.debug(log, "delete in h2 database registry with id=", "databaseRegistryId", databaseRegistryId);
        h2DatabaseRepository.flush();
    }
}
