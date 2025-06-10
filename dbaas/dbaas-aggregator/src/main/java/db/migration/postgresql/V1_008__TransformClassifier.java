package db.migration.postgresql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.JdbcUtils;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.V3_TRANSFORMATION;

@Slf4j
public class V1_008__TransformClassifier extends BaseJavaMigration {
    private final String CLASSIFIER = "classifier";
    private final String NAMESPACE = "namespace";

    @Override
    public void migrate(Context context) throws Exception {
        log.info("Start java based migration");
        ObjectMapper mapper = new ObjectMapper();
        Connection connection = context.getConnection();
        List<Map<String, Object>> rowDatabases = JdbcUtils.queryForList(connection, "SELECT * FROM database");
        List<DatabaseRecord> databases = new ArrayList<>();
        for (Map row : rowDatabases) {
            DatabaseRecord databaseRecord = new DatabaseRecord(
                    UUID.fromString(row.get("id").toString()),
                    mapper.readValue(row.get(CLASSIFIER).toString(), TreeMap.class),
                    row.get(NAMESPACE).toString()
            );
            databases.add(databaseRecord);
        }
        List<DatabaseWithInvalidClassifier> databasesWithInvalidClassifier = databases.stream()
                .filter(databaseRegistry -> !isValidClassifierV2(databaseRegistry.getClassifier()))
                .map(DatabaseWithInvalidClassifier::new)
                .collect(Collectors.toList());
        if (!databasesWithInvalidClassifier.isEmpty()) {
            try {
                log.warn("There are logical databases with incorrect classifiers: \n {}. \n You can find an article about fixing this by path: " +
                                "dbaas git repository -> docs -> classifier_v3_migration_process.md -> Update existing classifiers",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(databasesWithInvalidClassifier));
            } catch (JsonProcessingException e) {
                log.warn("There are logical databases with incorrect classifiers: {} \n You can find an article about fixing this by path: " +
                        "dbaas git repository -> docs -> classifier_v3_migration_process.md -> Update existing classifiers", databasesWithInvalidClassifier);
            }
        }
        String sqlUpdateDatabase = "UPDATE public.database SET classifier=?, old_classifier=? WHERE id = ?;";

        connection.prepareStatement("ALTER TABLE public.database ADD CONSTRAINT correct_classifier " +
                "check(classifier ->>'V3_TRANSFORMATION' is not null or classifier ->>'scope' is not null) NOT VALID;").execute();

        try {
            for (DatabaseRecord database : databases) {
                database.setOldClassifier(new TreeMap<>(database.getClassifier()));
                if (isValidClassifierV2(database.getClassifier())) {
                    database.setClassifier(new TreeMap<>(migrateV2ClassifierToV3(database.getClassifier(), database.getNamespace())));
                } else {
                    TreeMap<String, Object> classiferV3 = new TreeMap<>();
                    classiferV3.put(V3_TRANSFORMATION, "fail");
                    database.setClassifier(classiferV3);
                }
            }

            connection.prepareStatement("ALTER TABLE public.database ADD COLUMN IF NOT EXISTS old_classifier jsonb;").execute();

            connection.prepareStatement("DROP INDEX IF EXISTS classifier_and_type_index").execute();

            connection.prepareStatement("create UNIQUE INDEX IF NOT EXISTS classifier_and_type_index ON public.database USING" +
                    " btree (classifier, type) WHERE ((classifier ->> 'MARKED_FOR_DROP'::text) IS NULL and (classifier ->> 'V3_TRANSFORMATION'::text) IS NULL);").execute();


            PreparedStatement ps = connection.prepareStatement(sqlUpdateDatabase);
            for (DatabaseRecord database : databases) {
                PGobject jsonbClassifier = new PGobject();
                PGobject jsonbClassifier2 = new PGobject();
                jsonbClassifier.setType("jsonb");
                jsonbClassifier2.setType("jsonb");
                try {
                    jsonbClassifier.setValue(mapper.writeValueAsString(database.getClassifier()));
                    jsonbClassifier2.setValue(mapper.writeValueAsString(database.getOldClassifier()));
                } catch (JsonProcessingException e) {
                    log.debug(e.getMessage());
                    return;
                }

                ps.setObject(1, jsonbClassifier);
                ps.setObject(2, jsonbClassifier2);
                ps.setObject(3, UUID.fromString(database.getId().toString()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception exception) {
            connection.prepareStatement("ALTER TABLE public.database DROP CONSTRAINT if exists correct_classifier;").execute();
            throw exception;
        }
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    private boolean isValidClassifierV2(Map<String, Object> classifier) {
        return classifier != null && (((isServiceDb(classifier) && !classifier.containsKey("tenantId")) ||
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
}
