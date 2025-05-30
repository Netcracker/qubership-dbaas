package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;

@AllArgsConstructor
@ApplicationScoped
public class LogicalDbDbaasRepositoryImpl implements LogicalDbDbaasRepository {

    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    private DatabaseDbaasRepository databaseDbaasRepository;

    @Override
    public DatabaseRegistryDbaasRepository getDatabaseRegistryDbaasRepository() {
        return databaseRegistryDbaasRepository;
    }

    @Override
    public DatabaseDbaasRepository getDatabaseDbaasRepository() {
        return databaseDbaasRepository;
    }
}
