package org.qubership.cloud.dbaas.config.pg;

import com.google.common.base.Strings;
import org.qubership.cloud.dbaas.JdbcUtils;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import static org.qubership.cloud.dbaas.Constants.NAMESPACE;
import static org.qubership.cloud.dbaas.service.AbstractDbaasAdapterRESTClient.CLASSIFIER;

@Slf4j
public class PostgresqlClassifierMigration {

    private DataSource dataSource;

    public PostgresqlClassifierMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void afterPropertiesSet() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            List<Map<String, Object>> rowDatabases = JdbcUtils.queryForList(connection, "select * from database where database.id not in (SELECT database_id " +
                    "FROM public.classifier) and (database.classifier not in (SELECT classifier " +
                    "FROM public.classifier) or database.classifier ? 'MARKED_FOR_DROP');");

            List<DatabaseRegistryToSave> databases = new ArrayList<>();
            for (Map row : rowDatabases) {
                DatabaseRegistryToSave databaseRegistryToSave = new DatabaseRegistryToSave();
                databaseRegistryToSave.setDatabaseId(row.get("id").toString());
                if (!Strings.isNullOrEmpty((String) row.get(NAMESPACE))) {
                    databaseRegistryToSave.setNamespace(row.get(NAMESPACE).toString());
                }
                PGobject classifier = new PGobject();
                classifier.setType("jsonb");
                classifier.setValue(row.get(CLASSIFIER).toString());
                databaseRegistryToSave.setClassifier(classifier);
                if (row.get("time_db_creation") != null) {
                    databaseRegistryToSave.setTimeDbCreation(row.get("time_db_creation").toString());
                }
                databaseRegistryToSave.setType(row.get("type").toString());
                databases.add(databaseRegistryToSave);
            }


            String sqlInsertDatabase = "INSERT INTO public.classifier (id, classifier, namespace, type, time_db_creation, database_id)" +
                    " VALUES (?, ?, ?, ?, ?, ?);";

            PreparedStatement preparedStatement = connection.prepareStatement(sqlInsertDatabase);
            for (DatabaseRegistryToSave databaseRegistryToSave : databases) {
                preparedStatement.setObject(1, UUID.randomUUID());
                preparedStatement.setObject(2, databaseRegistryToSave.getClassifier());
                preparedStatement.setObject(3, databaseRegistryToSave.getNamespace());
                preparedStatement.setObject(4, databaseRegistryToSave.getType());
                preparedStatement.setObject(5, Timestamp.valueOf(databaseRegistryToSave.getTimeDbCreation()));
                preparedStatement.setObject(6, UUID.fromString(databaseRegistryToSave.getDatabaseId()));
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        }
    }

    @Data
    private static class DatabaseRegistryToSave {
        PGobject classifier;
        String timeDbCreation;
        String namespace;
        String type;
        String databaseId;

    }
}
