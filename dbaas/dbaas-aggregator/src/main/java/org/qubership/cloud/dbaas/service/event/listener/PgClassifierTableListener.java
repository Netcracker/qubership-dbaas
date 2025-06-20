package org.qubership.cloud.dbaas.service.event.listener;

import io.agroal.api.AgroalDataSource;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;

import java.util.UUID;

@Slf4j
public class PgClassifierTableListener extends AbstractPgTableListener {

    private final DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    public PgClassifierTableListener(AgroalDataSource dataSource,
                                     DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository) {
        this.databaseRegistryDbaasRepository = databaseRegistryDbaasRepository;
        this.dataSource = dataSource;
        this.connectionStatement = "LISTEN classifier_table_changes_event";
    }

    @Override
    protected void reloadH2Cache(UUID id) {
        synchronized (databaseRegistryDbaasRepository.getMutex()) {
            databaseRegistryDbaasRepository.reloadDatabaseRegistryH2Cache(id);
        }
    }

    @Override
    protected String tableName() {
        return "classifier";
    }
}
