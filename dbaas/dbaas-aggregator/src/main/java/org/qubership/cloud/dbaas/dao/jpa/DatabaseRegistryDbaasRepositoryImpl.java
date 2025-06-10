package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2DatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import static org.qubership.cloud.dbaas.Constants.MICROSERVICE_NAME;
import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.Constants.SCOPE;
import static org.qubership.cloud.dbaas.Constants.SCOPE_VALUE_TENANT;
import static org.qubership.cloud.dbaas.config.ServicesConfig.DBAAS_REPOSITORIES_MUTEX;
import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;


@Slf4j
@ApplicationScoped
@Transactional
public class DatabaseRegistryDbaasRepositoryImpl implements DatabaseRegistryDbaasRepository {
    private static final RetryPolicy<Void> H2_DELETE_RETRY_POLICY =
            new RetryPolicy<Void>().withMaxRetries(2).withDelay(Duration.ofSeconds(1));

    private DatabasesRepository databasesRepository;

    private H2DatabaseRepository h2DatabaseRepository;
    private DatabaseRegistryRepository databaseRegistryRepository;
    private H2DatabaseRegistryRepository h2DatabaseRegistryRepository;

    public DatabaseRegistryDbaasRepositoryImpl(DatabasesRepository databasesRepository,
                                               H2DatabaseRepository h2DatabaseRepository,
                                               DatabaseRegistryRepository databaseRegistryRepository,
                                               H2DatabaseRegistryRepository h2DatabaseRegistryRepository) {
        this.databasesRepository = databasesRepository;
        this.h2DatabaseRepository = h2DatabaseRepository;
        this.databaseRegistryRepository = databaseRegistryRepository;
        this.h2DatabaseRegistryRepository = h2DatabaseRegistryRepository;
    }

    public Object getMutex() {
        return DBAAS_REPOSITORIES_MUTEX;
    }

    public List<DatabaseRegistry> findAnyLogDbRegistryTypeByNamespace(String namespace) {
        log.debug("Search all logical databases registry by namespace={}", namespace);
        List<DatabaseRegistry> databaseList = doGet(() -> databaseRegistryRepository.findByNamespace(namespace), ex -> {
            log.debug("Catch exception = {} while trying to find logical databases Registry by namespace in Postgre, go to h2 database", ex.getMessage());
            return h2DatabaseRegistryRepository.findByNamespace(namespace).stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();
        });
        log.debug("Was found {} logical database registry in namespace={}", databaseList.size(), namespace);
        return databaseList;
    }

    @Override
    public List<DatabaseRegistry> findAnyLogDbTypeByNameAndOptionalParams(String name, @Nullable String namespace) {
        log.debug("Search all logical databases by namespace={} and name={}", namespace, name);
        List<DatabaseRegistry> databaseList;
        if (StringUtils.isNotBlank(namespace)) {
            List<DatabaseRegistry> databaseRegistries = findAnyLogDbRegistryTypeByNamespace(namespace);
            databaseList = databaseRegistries.stream()
                    .filter(databaseRegistry -> name.equals(databaseRegistry.getName()))
                    .toList();
        } else {
            databaseList = doGet(() -> databasesRepository.findAnyLogDbTypeByName(name), ex -> {
                log.debug("Catch exception = {} while trying to find logical database by namespace in Postgre, go to h2 database", ex.getMessage());
                return h2DatabaseRepository.findAnyLogDbTypeByName(name).stream().map(org.qubership.cloud.dbaas.entity.h2.Database::asPgEntity).toList();
            }).stream().map(Database::getDatabaseRegistry).flatMap(List::stream).toList();
        }
        log.debug("Was found {} logical database in namespace={} with name={}", databaseList.size(), namespace, name);
        return databaseList;
    }

    @Override
    public List<DatabaseRegistry> findInternalDatabaseRegistryByNamespace(String namespace) {
        log.debug("Search internal logical databaseRegistries by namespace {}", namespace);
        List<DatabaseRegistry> databaseRegistries = doGet(() -> databaseRegistryRepository.findByNamespace(namespace), ex -> {
            log.debug("Catch exception = {} while trying to find internal logical database by namespace in Postgre, go to h2 database",
                    ex.getMessage());
            return h2DatabaseRegistryRepository.findByNamespace(namespace).stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();
        });
        List<DatabaseRegistry> databaseRegistriesList = databaseRegistries.stream()
                .filter(database -> !database.getDatabase().isExternallyManageable())
                .collect(Collectors.toList());
        log.debug("Was found {} internal logical databaseRegistries in namespace={}", databaseRegistriesList.size(), namespace);
        return databaseRegistriesList;
    }

    @Override
    public DatabaseRegistry saveExternalDatabase(DatabaseRegistry databaseRegistry) {
        log.debug("Save external manageable logical database with classifier {} and type={}", databaseRegistry.getClassifier(), databaseRegistry.getType());
        return save(databaseRegistry);
    }

    @Override
    public DatabaseRegistry saveInternalDatabase(DatabaseRegistry databaseRegistry) {
        if (databaseRegistry.isExternallyManageable()) {
            throw new RuntimeException(String.format("Unable to save database %s, because flag external manageable should be false", databaseRegistry.getName()));
        }
        if (StringUtils.isBlank(databaseRegistry.getAdapterId())) {
            throw new RuntimeException(String.format("Unable to save database %s, because adapterId=%s is null or empty", databaseRegistry.getName(), databaseRegistry.getAdapterId()));
        }
        if (StringUtils.isBlank(databaseRegistry.getPhysicalDatabaseId())) {
            throw new RuntimeException(String.format("Unable to save database %s, because physicalDbId=%s is null or empty", databaseRegistry.getName(), databaseRegistry.getPhysicalDatabaseId()));
        }
        log.debug("Save internal logical database with classifier {} and type {}", databaseRegistry.getClassifier(), databaseRegistry.getType());
        return save(databaseRegistry);
    }

    @Override
    public DatabaseRegistry saveAnyTypeLogDb(DatabaseRegistry databaseRegistry) {
        log.debug("Save logical database with classifier {} and type {}", databaseRegistry.getClassifier(), databaseRegistry.getType());
        return save(databaseRegistry);
    }

    public List<DatabaseRegistry> findAllTenantDatabasesInNamespace(String namespace) {
        return databaseRegistryRepository.findByNamespace(namespace).stream()
                .filter(dbr -> SCOPE_VALUE_TENANT.equals(dbr.getClassifier().get(SCOPE))).toList();

    }

    public List<DatabaseRegistry> findAllTransactionalDatabaseRegistries(String namespace) {
        return databaseRegistryRepository.findAllByNamespaceAndDatabase_BgVersionNull(namespace);
    }

    @Override
    public void delete(DatabaseRegistry databaseRegistry) {
        log.debug("Delete logical database with classifier {} and type {}", databaseRegistry.getClassifier(), databaseRegistry.getType());
        Database database = databaseRegistry.getDatabase();
        synchronized (getMutex()) {
            deleteDatabase(database.getId(), databaseRegistry.getId());
            safeDeleteAndFlushDatabaseRegistry(databaseRegistry.getId());
        }
    }

    private void deleteDatabase(UUID databaseRegistryId) {
        Optional<DatabaseRegistry> databaseRegistry = databaseRegistryRepository.findByIdOptional(databaseRegistryId);
        if (databaseRegistry.isPresent() && databaseRegistry.get().getDatabase() != null) {
            deleteDatabase(databaseRegistry.get().getDatabase().getId(), databaseRegistry.get().getId());
        }
    }

    private void deleteDatabase(UUID databaseId, UUID databaseRegistryId) {
        if (databaseId == null) {
            return;
        }
        Database database = databasesRepository.findByIdOptional(databaseId).orElseThrow();
        DatabaseRegistry databaseRegistry = database.getDatabaseRegistry().stream()
                .filter(dbReg -> dbReg.getId().equals(databaseRegistryId)).findFirst().orElseThrow();

        database.getDatabaseRegistry().remove(databaseRegistry);
        if (database.getDatabaseRegistry().isEmpty()) {
            databasesRepository.delete(database);
        } else {
            databasesRepository.persist(database);
        }
    }

    @Override
    public void deleteExternalDatabases(List<Database> databases, String namespace) {
        List<DatabaseRegistry> databaseRegistries = databases.stream().filter(Database::isExternallyManageable).flatMap(d -> d.getDatabaseRegistry().stream()).toList();
        long count = 0;
        for (DatabaseRegistry databaseRegistry : databaseRegistries) {
            try {
                deleteById(databaseRegistry.getId());
                log.info("External database registry in {} with classifier {} was dropped", namespace, databaseRegistry.getClassifier());
                count++;
            } catch (Exception ex) {
                log.error("Error happened during dropping external database registry id {}, with message {}", databaseRegistry.getId(), ex);
            }
        }

        log.info("Was successfully dropped {} external logical databases in namespace={}", count, namespace);
    }


    @Override
    public void deleteById(UUID databaseRegistryId) {
        log.debug("Delete database registry with id={}", databaseRegistryId);
        synchronized (getMutex()) {
            deleteDatabase(databaseRegistryId);
            Failsafe.with(H2_DELETE_RETRY_POLICY).run(() -> safeDeleteAndFlushDatabaseRegistry(databaseRegistryId));
        }
    }

    @Override
    public Optional<DatabaseRegistry> findDatabaseRegistryById(UUID id) {
        return doGet(() -> databaseRegistryRepository.findByIdOptional(id), ex -> {
            log.debug("Catch exception = {} while trying to find database registry by id in Postgresql, go to h2 database",
                    ex.getMessage());
            return h2DatabaseRegistryRepository.findByIdOptional(id).map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity);
        });
    }

    @Override
    public List<DatabaseRegistry> findExternalDatabaseRegistryByNamespace(String namespace) {
        log.debug("Search external logical database registries with namespace {}", namespace);
        List<DatabaseRegistry> databaseRegistries = doGet(() -> databaseRegistryRepository.findByNamespace(namespace),
                ex -> {
                    log.debug("Catch exception = {} while trying to find external logical database in Postgre, go to h2 database",
                            ex.getMessage());
                    return h2DatabaseRegistryRepository.findByNamespace(namespace).stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();
                });
        List<DatabaseRegistry> externalDatabases = databaseRegistries.stream()
                .filter(db -> db.getDatabase().isExternallyManageable()).collect(Collectors.toList());
        log.debug("Was found {} external logical database with namespace {}", externalDatabases.size(), namespace);
        return externalDatabases;
    }

    @Override
    public List<DatabaseRegistry> findAllInternalDatabases() {
        List<DatabaseRegistry> databases = doGet(() -> databaseRegistryRepository.listAll(), ex -> {
            log.debug("Catch exception = {} while trying to find internal logical databases in Postgre, go to h2 database",
                    ex.getMessage());
            return h2DatabaseRegistryRepository.listAll().stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();
        });
        List<DatabaseRegistry> databaseList = databases.stream()
                .filter(database -> !database.isExternallyManageable())
                .collect(Collectors.toList());

        log.debug("Was found {} all internal logical databases", databaseList.size());
        return databaseList;
    }

    @Override
    public List<DatabaseRegistry> findAllDatabaseRegistersAnyLogType() {
        List<DatabaseRegistry> databases = doGet(() -> databaseRegistryRepository.listAll(), ex -> {
            log.debug("Catch exception = {} while trying to find all logical databases in postgresql, go to h2 database",
                    ex.getMessage());
            return h2DatabaseRegistryRepository.listAll().stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();

        });
        log.debug("Was found {} database any logical type", databases.size());
        return databases;
    }

    @Override
    public List<DatabaseRegistry> findAllDatabasesAnyLogTypeFromCache() {
        List<DatabaseRegistry> databaseRegistries = h2DatabaseRegistryRepository.listAll().stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();
        log.debug("Was found {} database any logical type", databaseRegistries.size());
        return databaseRegistries;
    }

    @Override
    public List<DatabaseRegistry> saveAll(List<DatabaseRegistry> databaseList) {
        List<DatabaseRegistry> savedDatabases = new ArrayList<>();
        for (DatabaseRegistry dr : databaseList) {
            save(dr);
        }
        return savedDatabases;
    }

    @Override
    public Optional<DatabaseRegistry> getDatabaseByClassifierAndType(Map<String, Object> classifier, String type) {
        return doGet(() -> databaseRegistryRepository.findDatabaseRegistryByClassifierAndType(new TreeMap<>(classifier), type), ex -> {
            log.debug("Catch exception = {} while trying to find logical databases by classifier and type in Postgre, go to h2 database", ex.getMessage());
            return h2DatabaseRegistryRepository.findDatabaseRegistryByClassifierAndType(new TreeMap<>(classifier), type).map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity);
        });
    }

    @Override
    public DatabaseRegistry getDatabaseByOldClassifierAndType(Map<String, Object> classifier, String type) {
        return doGet(() -> databasesRepository.findByOldClassifierAndType(new TreeMap<>(classifier), type).getDatabaseRegistry().get(0), ex -> {
            log.debug("Catch exception = {} while trying to find logical databases by classifier and type in Postgre, go to h2 database", ex.getMessage());
            return h2DatabaseRepository.findByOldClassifierAndType(new TreeMap<>(classifier), type).map(db -> db.getDatabaseRegistry().get(0).asPgEntity()).orElse(null);
        });
    }

    public void reloadDatabaseRegistryH2Cache(UUID databaseRegistryId) {
        log.debug("reload in h2 databaseRegistry with id= {}", databaseRegistryId);
        Optional<DatabaseRegistry> databaseRegistry = databaseRegistryRepository.findByIdOptional(databaseRegistryId);
        safeDeleteAndFlushDatabaseRegistry(databaseRegistryId);
        databaseRegistry.ifPresent(value -> QuarkusTransaction.requiringNew().run(() -> {
            log.debug("save in h2 databaseRegistry= {}", value);
            h2DatabaseRepository.findByIdOptional(value.getDatabase().getId()).ifPresentOrElse(
                    db -> {
                        db.getDatabaseRegistry().add(value.asH2Entity(db));
                        h2DatabaseRepository.merge(db);
                    },
                    () -> h2DatabaseRepository.merge(value.getDatabase().asH2Entity())
            );
        }));
        log.info("finished reload in databaseregistry with id = {}", databaseRegistryId);
    }

    @Override
    public void deleteOnlyTransactionalDatabaseRegistries(List<DatabaseRegistry> databaseRegistries) {
        log.debug("Delete classifiers from databases with classifier {}", databaseRegistries);
        databaseRegistries.forEach(dbr -> {
            Database database = dbr.getDatabase();
            database.getDatabaseRegistry().remove(dbr);
            databasesRepository.persist(database);
        });

    }

    public void deleteOnlyTransactionalDatabaseRegistries(String namespace) {
        databaseRegistryRepository.deleteOnlyTransactionalDatabaseRegistries(namespace);
    }

    public List<DatabaseRegistry> findAllVersionedDatabaseRegistries(String namespace) {
        return databaseRegistryRepository.findAllByNamespaceAndDatabase_BgVersionNotNull(namespace);
    }

    private DatabaseRegistry save(DatabaseRegistry databaseRegistry) {
        if (databaseRegistry.getConnectionProperties() != null) {
            databaseRegistry.getConnectionProperties().stream().filter(cp -> cp.containsKey(ROLE)).forEach(cp -> cp.put(ROLE, ((String) cp.get(ROLE)).toLowerCase()));
        }
        log.debug("save classifier = {}", databaseRegistry);
        synchronized (getMutex()) {
            Database savedDatabase = databaseRegistry.getDatabase();
            if (databaseRegistry.getId() == null) {
                databasesRepository.persistAndFlush(savedDatabase);
            } else {
                EntityManager entityManager = databasesRepository.getEntityManager();
                entityManager.merge(savedDatabase);
                entityManager.flush();
            }
            Optional<DatabaseRegistry> savedDatabaseRegistry = savedDatabase.getDatabaseRegistry().stream()
                    .filter(v -> v.getClassifier().equals(databaseRegistry.getClassifier())).findFirst();
            log.debug("saved classifier = {}", savedDatabaseRegistry);
            return savedDatabaseRegistry.orElseThrow();
        }
    }

    @Override
    public List<DatabaseRegistry> findDatabasesByMicroserviceNameAndNamespace(String microserviceName, String namespace) {
        log.debug("find DBs by microserviceName={} and namespace={}", microserviceName, namespace);
        List<DatabaseRegistry> databaseRegistries = doGet(() -> databaseRegistryRepository.findByNamespace(namespace), ex -> {
            log.debug("Catch exception = {} while trying to find logical databases by microservice name and namespace in Postgre, go to h2 database", ex.getMessage());
            return h2DatabaseRegistryRepository.findByNamespace(namespace).stream().map(org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry::asPgEntity).toList();
        });
        List<DatabaseRegistry> databasesList = databaseRegistries.stream()
                .filter(database -> microserviceName.equals(database.getClassifier().get(MICROSERVICE_NAME)))
                .filter(database -> !database.isMarkedForDrop())
                .collect(Collectors.toList());
        log.debug("Was found {} databases", databasesList.size());
        return databasesList;
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

    @Transactional(REQUIRES_NEW)
    protected void safeDeleteAndFlushDatabaseRegistry(UUID databaseRegistryId) {
        Optional<org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry> databaseRegistryOptional = h2DatabaseRegistryRepository.findByIdOptional(databaseRegistryId);
        if (databaseRegistryOptional.isEmpty()) {
            return;
        }
        org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry databaseRegistry = databaseRegistryOptional.get();

        org.qubership.cloud.dbaas.entity.h2.Database database = databaseRegistry.getDatabase();

        database.getDatabaseRegistry().remove(databaseRegistry);
        if (database.getDatabaseRegistry().isEmpty()) {
            h2DatabaseRepository.deleteById(database.getId());
        } else {
            h2DatabaseRepository.persist(database);
        }

        log.debug("delete in h2 database registry with id= {}", databaseRegistryId);
        h2DatabaseRepository.flush();
    }

}
