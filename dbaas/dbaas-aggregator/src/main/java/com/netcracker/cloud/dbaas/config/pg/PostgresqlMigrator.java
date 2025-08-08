package org.qubership.cloud.dbaas.config.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.JdbcUtils;
import org.qubership.cloud.dbaas.entity.pg.Database;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.sql.DataSource;

@Slf4j
public class PostgresqlMigrator {
    private static final String CLASSIFIER = "classifier";
    private static final String OLD_CLASSIFIER = "old_classifier";

    private DataSource dataSource;

    public PostgresqlMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void afterPropertiesSet() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> rowDatabases = JdbcUtils.queryForList(connection,
                    "SELECT * FROM database where classifier ->> 'V3_TRANSFORMATION' = 'fail' " +
                            "or not (classifier ? 'scope' and " +
                            "(" +
                            "(classifier ->> 'scope' = 'tenant' and classifier ? 'tenantId') " +
                            "or " +
                            "(classifier ->> 'scope' = 'service') " +
                            ") and classifier ? 'namespace' and classifier ? 'microserviceName');");
            log.info("Found {} incorrect databases registered in dbaas", rowDatabases.size());
            for (Map row : rowDatabases) {
                Database database = new Database();
                database.setId(UUID.fromString(row.get("id").toString()));
                try {
                    TreeMap classifier = mapper.readValue(row.get(CLASSIFIER).toString(), TreeMap.class);
                    database.setClassifier(classifier);
                    if (row.get(OLD_CLASSIFIER) != null) {
                        database.setOldClassifier(mapper.readValue(row.get(OLD_CLASSIFIER).toString(), TreeMap.class));
                    }
                    log.error("Incorrect database with id={}, migrated classifier={} and old classifier={}", database.getId(), classifier, database.getOldClassifier());
                } catch (JsonProcessingException e) {
                    log.error("Incorrect database with id={}, can't parse classifier", database.getId());
                }
            }
            if (!rowDatabases.isEmpty()) {
                throw new RuntimeException("There are logical databases with not migrated classifiers, update to new dbaas release version is not possible");
            }
        }
    }
}
