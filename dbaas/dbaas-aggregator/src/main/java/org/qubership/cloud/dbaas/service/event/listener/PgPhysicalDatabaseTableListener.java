package org.qubership.cloud.dbaas.service.event.listener;


import io.agroal.api.AgroalDataSource;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;

import java.util.UUID;

@Slf4j
public class PgPhysicalDatabaseTableListener extends AbstractPgTableListener {

    private PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;

    public PgPhysicalDatabaseTableListener(AgroalDataSource dataSource,
                                           PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository) {
        this.dataSource = dataSource;
        this.physicalDatabaseDbaasRepository = physicalDatabaseDbaasRepository;
        this.connectionStatement = "LISTEN physical_database_table_changes_event";
    }

    @Override
    protected void reloadH2Cache(UUID id) {
        synchronized (physicalDatabaseDbaasRepository.getMutex()) {
            physicalDatabaseDbaasRepository.reloadH2Cache(id.toString());
        }
    }

    @Override
    protected String tableName() {
        return "physical database";
    }
}
