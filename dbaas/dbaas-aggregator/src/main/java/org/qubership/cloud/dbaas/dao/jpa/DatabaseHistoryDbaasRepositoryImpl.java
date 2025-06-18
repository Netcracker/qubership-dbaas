package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.entity.pg.DatabaseHistory;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseHistoryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseHistoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@ApplicationScoped
public class DatabaseHistoryDbaasRepositoryImpl implements DatabaseHistoryDbaasRepository {

    DatabaseHistoryRepository historyRepository;

    @Override
    public Integer getLastVersionByName(String dbName) {
        return historyRepository.findFirstVersionByDbNameOrderByVersionDesc(dbName);
    }

    @Override
    public DatabaseHistory save(DatabaseHistory databaseHistory) {
        historyRepository.persist(databaseHistory);
        return databaseHistory;
    }
}
