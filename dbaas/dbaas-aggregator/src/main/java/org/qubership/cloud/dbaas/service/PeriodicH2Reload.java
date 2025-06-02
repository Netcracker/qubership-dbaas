package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class PeriodicH2Reload {

    @Inject
    DatabaseDbaasRepository databaseDbaasRepository;
    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;

    @Scheduled(every = "${dbaas.h2.sync.every}s")
    @Transactional
    public void refreshH2Periodically() {
        log.debug("Running periodic h2 synchronization with pg");
        synchronized (databaseDbaasRepository.getMutex()) {
            databaseDbaasRepository.reloadH2Cache();
        }
        synchronized (physicalDatabaseDbaasRepository.getMutex()) {
            physicalDatabaseDbaasRepository.reloadH2Cache();
        }
    }
}
