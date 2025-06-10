package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.DatabaseHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

/**
 * DatabaseHistory object cannot be used for fetching because it contains database object which can change its own structure.
 * If you need use it in future it can be done via spring-data-projection: https://www.baeldung.com/spring-data-jpa-projections#1-closed-projections
 */
@ApplicationScoped
public class DatabaseHistoryRepository implements PanacheRepositoryBase<DatabaseHistory, UUID> {

    public Integer findFirstVersionByDbNameOrderByVersionDesc(String dbName) {
        Optional<DatabaseHistory> databaseHistory = find("name", Sort.by("version", Sort.Direction.Descending), dbName).firstResultOptional();
        return databaseHistory.isPresent() ? databaseHistory.get().getVersion() : null;
    }
}
