package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.DatabaseHistory;

public interface DatabaseHistoryDbaasRepository {

    Integer getLastVersionByName(String dbName);

    DatabaseHistory save(DatabaseHistory databaseHistory);
}
