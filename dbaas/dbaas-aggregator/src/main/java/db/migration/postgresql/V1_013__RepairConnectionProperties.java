package db.migration.postgresql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.JdbcUtils;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.qubership.cloud.dbaas.Constants.DUPLICATED_DATABASE;
import static org.qubership.cloud.dbaas.Constants.V3_TRANSFORMATION;

@Slf4j
public class V1_013__RepairConnectionProperties extends BaseJavaMigration {
    private final String CLASSIFIER = "classifier";
    private final String OLD_CLASSIFIER = "old_classifier";
    private final String CONNECTION_PROPERTIES = "connection_properties";
    private final String NAMESPACE = "namespace";
    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        log.info("Start repair connection_properties and classifier");
        Connection connection = context.getConnection();

        List<Map<String, Object>> rowDatabases = JdbcUtils.queryForList(connection, "SELECT * FROM public.database " +
                "where (classifier -> 'V3_TRANSFORMATION' ? 'fail');");
        List<Map<String, Object>> rowDuplicatedDatabases = JdbcUtils.queryForList(connection, "SELECT * FROM public.database " +
                "where (old_classifier is null and classifier ->>'scope' is null and classifier ->>'V3_TRANSFORMATION' is null);");
        List<DatabaseRecord> databases = new ArrayList<>();
        for (Map row : rowDatabases) {
            DatabaseRecord databaseRecord = new DatabaseRecord(
                    UUID.fromString(row.get("id").toString()),
                    mapper.readValue(row.get(CLASSIFIER).toString(), TreeMap.class),
                    row.get(NAMESPACE).toString(),
                    row.get("type").toString()
            );
            databaseRecord.setOldClassifier(mapper.readValue(row.get(OLD_CLASSIFIER).toString(), TreeMap.class));
            databases.add(databaseRecord);

        }
        List<DuplicatedDatabase> duplicatedDatabases = getDuplicatedDatabases(rowDuplicatedDatabases);

        ListIterator<DatabaseRecord> databaseListIterator = databases.listIterator();
        while (databaseListIterator.hasNext()) {
            var database = databaseListIterator.next();
            if (isValidClassifierV2(database.getOldClassifier())) {
                final TreeMap<String, Object> migratedV3Classifier = new TreeMap<>(migrateV2ClassifierToV3(database.getOldClassifier(), database.getNamespace()));
                boolean classifierAlreadyExists = isMigratedV3ClassifierDuplicate(connection, migratedV3Classifier, database.getType());
                if (classifierAlreadyExists) {
                    databaseListIterator.remove();
                } else {
                    database.setClassifier(migratedV3Classifier);
                }
            }
        }
        String sqlUpdateDatabase = "UPDATE public.database SET classifier=?, old_classifier=? WHERE id = ?;";
        PreparedStatement ps = connection.prepareStatement(sqlUpdateDatabase);
        for (DatabaseRecord databaseRecord : databases) {
            if (jsonbClassifier(ps, databaseRecord.getClassifier(), databaseRecord.getOldClassifier()))
                return;

            ps.setObject(3, UUID.fromString(databaseRecord.getId().toString()));
            ps.addBatch();
        }
        ps.executeBatch();

        String updateDuplicatedDatabasesSql = "UPDATE public.database SET classifier=?, old_classifier=?, connection_properties=? WHERE id = ?;";
        ps = connection.prepareStatement(updateDuplicatedDatabasesSql);
        for (DuplicatedDatabase duplicatedDatabase : duplicatedDatabases) {
            if (jsonbClassifier(ps, duplicatedDatabase.getClassifier(), duplicatedDatabase.getOldClassifier()))
                return;

            ps.setObject(3, duplicatedDatabase.connectionProperty);
            ps.setObject(4, UUID.fromString(duplicatedDatabase.getId().toString()));
            ps.addBatch();
        }
        ps.executeBatch();


    }

    private boolean isMigratedV3ClassifierDuplicate(Connection connection, TreeMap<String, Object> migratedV3Classifier, String type) throws SQLException {
        PGobject jsonbClassifier = new PGobject();
        jsonbClassifier.setType("jsonb");
        try {
            jsonbClassifier.setValue(mapper.writeValueAsString(migratedV3Classifier));
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
            return true;
        }
        PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM public.database where classifier = ? and type = ?;");
        ps.setObject(1, jsonbClassifier);
        ps.setObject(2, type);
        ResultSet rs = ps.executeQuery();
        rs.next();
        int duplicate = rs.getInt(1);
        return duplicate != 0;
    }

    private boolean jsonbClassifier(PreparedStatement ps, SortedMap<String, Object> classifier, SortedMap<String, Object> oldClassifier) throws SQLException {
        PGobject jsonbClassifier = new PGobject();
        PGobject jsonbClassifier2 = new PGobject();
        jsonbClassifier.setType("jsonb");
        jsonbClassifier2.setType("jsonb");
        try {
            jsonbClassifier.setValue(mapper.writeValueAsString(classifier));
            jsonbClassifier2.setValue(mapper.writeValueAsString(oldClassifier));
        } catch (JsonProcessingException e) {
            log.warn(e.getMessage());
            return true;
        }

        ps.setObject(1, jsonbClassifier);
        ps.setObject(2, jsonbClassifier2);
        return false;
    }

    private List<DuplicatedDatabase> getDuplicatedDatabases(List<Map<String, Object>> rowDuplicatedDatabases) throws JsonProcessingException {
        List<DuplicatedDatabase> duplicatedDatabases = new ArrayList<>();
        for (Map row : rowDuplicatedDatabases) {
            DuplicatedDatabase database = new DuplicatedDatabase();
            database.setId(UUID.fromString(row.get("id").toString()));
            TreeMap<String, Object> classifier = mapper.readValue(row.get(CLASSIFIER).toString(), TreeMap.class);
            classifier.put(DUPLICATED_DATABASE, true);
            database.setOldClassifier(classifier);
            if (isValidClassifierV2(database.getOldClassifier())) {
                database.setClassifier(new TreeMap<>(migrateV2ClassifierToV3(database.getOldClassifier(), row.get(NAMESPACE).toString())));
            } else {
                TreeMap<String, Object> classiferV3 = new TreeMap<>();
                classiferV3.put(V3_TRANSFORMATION, "fail");
                database.setClassifier(classiferV3);
            }
            if (row.get(CONNECTION_PROPERTIES) != null) {
                String connectionProperties = row.get(CONNECTION_PROPERTIES).toString();
                if (connectionProperties.startsWith("[") && connectionProperties.endsWith("]")) {
                    database.setConnectionProperty(connectionProperties);
                } else {
                    if (connectionProperties.contains("\"role\":\"admin\"") ||
                            connectionProperties.contains("\"role\": \"admin\"")) {
                        database.setConnectionProperty("[" + connectionProperties + "]");
                    } else {
                        if (connectionProperties.equals("{}")) {
                            database.setConnectionProperty("[{\"role\":\"admin\"}]");
                        } else {
                            database.setConnectionProperty("[{\"role\":\"admin\", " + connectionProperties.substring(1) + "]");
                        }
                    }

                }
            }
            duplicatedDatabases.add(database);
        }
        return duplicatedDatabases;
    }

    private boolean isValidClassifierV2(Map<String, Object> classifier) {
        return classifier != null && (((!classifier.containsKey("tenantId")) ||
                (!isServiceDb(classifier) && classifier.containsKey("tenantId"))) &&
                classifier.containsKey("microserviceName"));
    }

    private boolean isServiceDb(Map<String, Object> classifier) {
        return classifier.containsKey("isServiceDb") && isServiceToBoolean(classifier.get("isServiceDb"))
                || classifier.containsKey("isService") && isServiceToBoolean(classifier.get("isService"));
    }

    private boolean isServiceToBoolean(Object isService) {
        if (isService instanceof String) {
            return isService.equals("true");
        }
        if (isService instanceof Boolean) {
            return (Boolean) isService;
        }
        return false;
    }

    private Map<String, Object> migrateV2ClassifierToV3(Map<String, Object> classifier, String namespace) {
        Map<String, Object> classifierV3 = null;
        classifierV3 = new TreeMap<>(classifier);
        Object isService = classifierV3.get("isServiceDb");
        if (isService == null) {
            isService = classifierV3.get("isService");
        }
        classifierV3.remove("isService");
        classifierV3.remove("isServiceDb");

        if (isService == null && classifier.containsKey("tenantId")) {
            classifierV3.put("scope", "tenant");
        } else {
            classifierV3.put("scope", "service");
        }
        if (!classifierV3.containsKey("namespace")) {
            classifierV3.put("namespace", namespace);
        }

        return classifierV3;
    }

    @Getter
    static class DatabaseWithInvalidClassifier {
        private UUID id;
        private SortedMap<String, Object> classifier;

        public DatabaseWithInvalidClassifier(DatabaseRecord databaseRecord) {
            this.id = databaseRecord.getId();
            this.classifier = databaseRecord.getClassifier();
        }

        @Override
        public String toString() {
            return "{" +
                    "id=" + id +
                    ", classifier=" + classifier +
                    '}';
        }
    }

    @Data
    @NoArgsConstructor
    static class DuplicatedDatabase {
        private UUID id;
        private SortedMap<String, Object> classifier;
        private SortedMap<String, Object> oldClassifier;
        private String connectionProperty;

        @Override
        public String toString() {
            return "{" +
                    "id=" + id +
                    ", classifier=" + classifier +
                    ", oldClassifier=" + oldClassifier +
                    ", connectionProperty=" + connectionProperty +
                    '}';
        }
    }

    @RequiredArgsConstructor
    @Data
    static class DatabaseRecord {

        @NonNull
        private UUID id;

        @NonNull
        private SortedMap<String, Object> classifier;

        private SortedMap<String, Object> oldClassifier;

        @NonNull
        private String namespace;
        @NonNull
        private String type;
    }
}
