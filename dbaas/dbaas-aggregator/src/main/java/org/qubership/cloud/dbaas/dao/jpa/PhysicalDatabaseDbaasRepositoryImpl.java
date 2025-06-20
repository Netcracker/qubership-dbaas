package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2PhysicalDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Stream;

@Slf4j
@AllArgsConstructor
@ApplicationScoped
@Transactional
public class PhysicalDatabaseDbaasRepositoryImpl implements PhysicalDatabaseDbaasRepository {

    private PhysicalDatabasesRepository physicalDatabasesRepository;
    private H2PhysicalDatabaseRepository h2PhysicalDatabaseRepository;
    private final static Object mutex = new Object();

    public Object getMutex() {
        return mutex;
    }

    public Stream<PhysicalDatabase> findByType(String type) {
        List<PhysicalDatabase> databaseList = doGet(() -> physicalDatabasesRepository.findByType(type), ex -> {
            log.warn("Catch exception = {} while trying to find physical databases by type in Postgre, go to h2 database", ex.getMessage());
            return h2PhysicalDatabaseRepository.findByType(type).stream().map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).toList();
        });
        log.debug("founded by type={} physical databases = {}", type, databaseList);
        return databaseList.stream();
    }

    public PhysicalDatabase findByPhysicalDatabaseIdentifier(String physicalDatabaseIdentifier) {
        return doGet(() -> physicalDatabasesRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseIdentifier), ex -> {
            log.warn("Catch exception = {} while trying to find physical databases by identifier in Postgre, go to h2 database", ex.getMessage());
            return h2PhysicalDatabaseRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseIdentifier).map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).orElse(null);
        });
    }

    public PhysicalDatabase save(PhysicalDatabase databaseRegistration) {
        if (databaseRegistration.getId() == null) {
            physicalDatabasesRepository.persistAndFlush(databaseRegistration);
        } else {
            EntityManager entityManager = physicalDatabasesRepository.getEntityManager();
            entityManager.merge(databaseRegistration);
            entityManager.flush();
        }
        return databaseRegistration;
    }

    public PhysicalDatabase findByAdapterAddress(String adapterAddress) {
        return doGet(() -> physicalDatabasesRepository.findByAdapterAddress(adapterAddress), ex -> {
            log.warn("Catch exception = {} while trying to find physical databases by adapter address in Postgre, go to h2 database", ex.getMessage());

            return h2PhysicalDatabaseRepository.findByAdapterAddress(adapterAddress).map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).orElse(null);
        });
    }

    public List<PhysicalDatabase> findByAdapterHost(String adapterHost) {
        return doGet(() -> physicalDatabasesRepository.findByAdapterAddressHost(adapterHost), ex -> {
            log.warn("Catch exception = {} while trying to find physical databases by adapter host in Postgres, go to h2 database", ex.getMessage());
            return h2PhysicalDatabaseRepository.findByAdapterAddressHost(adapterHost).stream().map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).toList();
        });
    }

    public List<PhysicalDatabase> findAll() {
        return doGet(() -> physicalDatabasesRepository.listAll(), ex -> {
            log.warn("Catch exception = {} while trying to find all physical databases in Postgre, go to h2 database", ex.getMessage());
            return h2PhysicalDatabaseRepository.listAll().stream().map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).toList();
        });
    }

    public PhysicalDatabase findByAdapterId(String adapterId) {
        return doGet(() -> physicalDatabasesRepository.findByAdapterId(adapterId), ex -> {
            log.warn("Catch exception = {} while trying to find physical database by adapterId in Postgre, go to h2 database", ex.getMessage());
            return h2PhysicalDatabaseRepository.findByAdapterId(adapterId).map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).orElse(null);
        });
    }

    @Override
    public Optional<PhysicalDatabase> findGlobalByType(String type) {
        List<PhysicalDatabase> globals = doGet(() -> physicalDatabasesRepository.findByTypeAndGlobal(type, true), ex -> {
            log.warn("Catch exception = {} while trying to find physical database by type in Postgre, go to h2 database", ex.getMessage());
            return h2PhysicalDatabaseRepository.findByTypeAndGlobal(type, true).stream().map(org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase::asPgEntity).toList();
        });
        return globals == null || globals.isEmpty()
                ? Optional.empty()
                : Optional.of(globals.get(0)); // there can be only one global physical db of each type
    }

    @Override
    public void delete(PhysicalDatabase physicalDatabase) {
        physicalDatabasesRepository.deleteById(physicalDatabase.getId());
        synchronized (mutex) {
            if (h2PhysicalDatabaseRepository.existsById(physicalDatabase.getId())) {
                h2PhysicalDatabaseRepository.deleteById(physicalDatabase.getId());
                h2PhysicalDatabaseRepository.flush();
            }
        }
    }

    public void reloadH2Cache() {
        log.debug("reload physical Database h2");
        List<PhysicalDatabase> foundedDatabases = physicalDatabasesRepository.listAll();
        h2PhysicalDatabaseRepository.deleteAll();
        h2PhysicalDatabaseRepository.flush();
        h2PhysicalDatabaseRepository.merge(foundedDatabases.stream().map(PhysicalDatabase::asH2Entity).toList());
        h2PhysicalDatabaseRepository.flush();
    }

    public void reloadH2Cache(String id) {
        log.debug("reload in h2 physical database with id = {}", id);
        Optional<PhysicalDatabase> database = physicalDatabasesRepository.findByIdOptional(id);
        if (h2PhysicalDatabaseRepository.existsById(id)) {
            log.debug("delete in h2 physical database with id = {}", id);
            h2PhysicalDatabaseRepository.deleteById(id);
            h2PhysicalDatabaseRepository.flush();
        }
        database.ifPresent(value -> {
            log.debug("save in h2 physical database = {}", value);
            h2PhysicalDatabaseRepository.merge(value.asH2Entity());
        });
        h2PhysicalDatabaseRepository.flush();
    }

    private <T> T doGet(Callable<T> action, Function<Exception, T> rollback) {
        try {
            return action.call();
        } catch (Exception e) {
            synchronized (mutex) {
                return QuarkusTransaction.requiringNew().call(() -> rollback.apply(e));
            }
        }
    }
}
