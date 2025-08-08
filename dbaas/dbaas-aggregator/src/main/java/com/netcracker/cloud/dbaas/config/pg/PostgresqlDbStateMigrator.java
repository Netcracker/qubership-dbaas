package org.qubership.cloud.dbaas.config.pg;

import org.qubership.cloud.dbaas.JdbcUtils;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

@Slf4j
public class PostgresqlDbStateMigrator {

    private DataSource dataSource;
    private final Map<String, DbState.DatabaseStateStatus> valuesMap;

    public PostgresqlDbStateMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        valuesMap = Map.of(
                "0", DbState.DatabaseStateStatus.PROCESSING,
                "1", DbState.DatabaseStateStatus.CREATED,
                "2", DbState.DatabaseStateStatus.DELETING,
                "3", DbState.DatabaseStateStatus.DELETING_FAILED,
                "4", DbState.DatabaseStateStatus.ARCHIVED,
                "5", DbState.DatabaseStateStatus.ORPHAN
        );
    }


    @PostConstruct
    public void afterPropertiesSet() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            List<Map<String, Object>> rowDatabases = JdbcUtils.queryForList(connection,
                    "SELECT id, state FROM database_state_info where database_state is null and state in ('0', '1', '2', '3', '4, 5');");
            log.info("Found {} with databases state", rowDatabases.size());
            if (rowDatabases.isEmpty()) {
                return;
            }

            String sqlUpdate = "UPDATE database_state_info SET database_state=? WHERE id = ?";
            PreparedStatement ps = connection.prepareStatement(sqlUpdate);
            for (Map<String, Object> row : rowDatabases) {
                String state = (String) row.get("state");
                log.debug("valuesMap = {}", valuesMap.get(state).name());
                ps.setObject(1, valuesMap.get(state).name());
                ps.setObject(2, row.get("id"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
