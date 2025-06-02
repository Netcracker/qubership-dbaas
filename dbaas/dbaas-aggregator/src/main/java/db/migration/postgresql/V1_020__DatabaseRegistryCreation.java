package db.migration.postgresql;

import com.google.common.base.Strings;
import org.qubership.cloud.dbaas.JdbcUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.qubership.cloud.dbaas.Constants.NAMESPACE;
import static org.qubership.cloud.dbaas.service.AbstractDbaasAdapterRESTClient.CLASSIFIER;

@Slf4j
public class V1_020__DatabaseRegistryCreation extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        connection.prepareStatement(
                "create table if not exists classifier" +
                        "                (" +
                        "                id                     uuid    not null," +
                        "                classifier             jsonb   not null," +
                        "                namespace              text," +
                        "                type                   text    not null," +
                        "                time_db_creation       timestamp," +
                        "                database_id            uuid    not null," +
                        "                primary key (id)," +
                        "                FOREIGN KEY (database_id) REFERENCES database (id) ON DELETE CASCADE" +
                        ");").execute();

        List<Map<String, Object>> rowDatabases = JdbcUtils.queryForList(connection, "SELECT * FROM public.database;");

        connection.prepareStatement("create UNIQUE INDEX IF NOT EXISTS database_registry_classifier_and_type_index ON public.classifier USING btree (classifier, type) WHERE ((classifier ->> 'MARKED_FOR_DROP'::text) IS NULL);").execute();


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

        PreparedStatement ps = connection.prepareStatement(sqlInsertDatabase);
        for (DatabaseRegistryToSave database : databases) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, database.getClassifier());
            ps.setObject(3, database.getNamespace());
            ps.setObject(4, database.getType());
            if (database.getTimeDbCreation() != null) {
                ps.setObject(5, Timestamp.valueOf(database.getTimeDbCreation()));
            } else {
                ps.setObject(5, null);
            }
            ps.setObject(6, UUID.fromString(database.getDatabaseId()));
            ps.addBatch();
        }
        ps.executeBatch();

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
