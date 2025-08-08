package com.netcracker.cloud.dbaas.service.event.listener;

import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

import java.sql.SQLException;

@ApplicationScoped
public class ApplicationListenerConfiguration {

    @Inject
    ManagedExecutor managedExecutor;

    void initListeners(@Observes StartupEvent event,
                       AgroalDataSource agroalDataSource,
                       DatabaseDbaasRepository databaseDbaasRepository,
                       DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository,
                       PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository) throws SQLException {
        managedExecutor.runAsync(new PgDatabaseTableListener(agroalDataSource, databaseDbaasRepository));
        managedExecutor.runAsync(new PgClassifierTableListener(agroalDataSource, databaseRegistryDbaasRepository));
        managedExecutor.runAsync(new PgPhysicalDatabaseTableListener(agroalDataSource, physicalDatabaseDbaasRepository));
    }
}
