package org.qubership.cloud.dbaas.service.event.listener;

import io.agroal.api.AgroalDataSource;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;

import java.util.UUID;

@Slf4j
public class PgDatabaseTableListener extends AbstractPgTableListener {

    private final DatabaseDbaasRepository databaseDbaasRepository;

    public PgDatabaseTableListener(AgroalDataSource dataSource,
                                   DatabaseDbaasRepository databaseDbaasRepository) {
        this.databaseDbaasRepository = databaseDbaasRepository;
        this.dataSource = dataSource;
        this.connectionStatement = "LISTEN database_table_changes_event";
    }

    @Override
    protected void reloadH2Cache(UUID id) {
        synchronized (databaseDbaasRepository.getMutex()) {
            databaseDbaasRepository.reloadH2Cache(id);
        }
    }

    @Override
    protected String tableName() {
        return "database";
    }
}
