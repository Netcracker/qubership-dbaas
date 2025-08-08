package com.netcracker.cloud.dbaas.repositories.dbaas;

import com.netcracker.cloud.dbaas.entity.pg.DatabaseHistory;

public interface DatabaseHistoryDbaasRepository {

    Integer getLastVersionByName(String dbName);

    DatabaseHistory save(DatabaseHistory databaseHistory);
}
