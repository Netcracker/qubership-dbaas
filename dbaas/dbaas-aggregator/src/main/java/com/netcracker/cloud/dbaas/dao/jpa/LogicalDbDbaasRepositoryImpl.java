package com.netcracker.cloud.dbaas.dao.jpa;

import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
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
