package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.cloud.dbaas.entity.shared.AbstractDbState.DatabaseStateStatus.PROCESSING;

@Slf4j
@ApplicationScoped
public class StartupDatabaseCleaner {

    @Inject
    DatabaseDbaasRepository databasesRepository;
    @Inject
    DbaaSHelper dbaaSHelper;
    @Inject
    DBaaService dBaaService;

    public void onStartup(@Observes StartupEvent event) {
        cleanProcessingDatabases();
    }

    @Transactional
    public void cleanProcessingDatabases() {
        log.info("Starting cleanup of the stale PROCESSING databases. Current pod name: {}", dbaaSHelper.getPodName());
        List<Database> dbsToCleanup = databasesRepository.findByDbState_StateAndDbState_PodName(PROCESSING, dbaaSHelper.getPodName())
                .stream()
                .filter(db -> !db.isMarkedForDrop())
                .toList();
        if (dbsToCleanup.isEmpty()) {
            log.info("No stale PROCESSING databases found");
        } else {
            log.info("Cleaning up stale PROCESSING databases: {}, pod name {}", dbsToCleanup, dbaaSHelper.getPodName());
            if (dbaaSHelper.isProductionMode()) {
                log.info("DbaaS in production mode. Marking PROCESSING databases as dropped");
                Map<String, List<DatabaseRegistry>> namespaceDbs = new HashMap<>();
                dbsToCleanup.forEach(database -> database.getDatabaseRegistry().forEach(
                        databaseRegistry -> {
                            String namespace = databaseRegistry.getNamespace();
                            List<DatabaseRegistry> databaseRegistries = namespaceDbs.getOrDefault(namespace, new ArrayList<>());
                            databaseRegistries.add(databaseRegistry);
                            namespaceDbs.put(namespace, databaseRegistries);
                        }
                ));
                for (Map.Entry<String, List<DatabaseRegistry>> entry : namespaceDbs.entrySet()) {
                    dBaaService.markForDrop(entry.getKey(), entry.getValue());
                }
            } else {
                log.info("DbaaS in dev mode. Deleting PROCESSING databases from repository");
                databasesRepository.deleteAll(dbsToCleanup);
            }
        }
    }
}
