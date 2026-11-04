package com.netcracker.cloud.dbaas.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The H2 schema under {@code db/migration/h2} is a hand-written copy of the PostgreSQL one, so a mirrored column can go
 * missing from it with no Java change to show for it. This test loads that schema into an in-memory H2 and checks that
 * every column the H2 entities map is declared there; a missing one belongs in that same file, which is edited in
 * place.
 *
 * <p>Column types are not compared: the mirror uses H2 types that need not match the JDBC type Hibernate maps a field
 * to, and a type it reports differently for is harmless as long as the column is there.
 */
class H2MirrorSchemaTest {

    /**
     * Any H2 entity, to locate and name the package the mirrors live in.
     */
    private static final Class<?> MIRROR_SAMPLE = com.netcracker.cloud.dbaas.entity.h2.Database.class;

    private static final String H2_JDBC_URL = "jdbc:h2:mem:mirror_schema_parity;DB_CLOSE_DELAY=-1";

    @Test
    void testH2Entities_MapOnlyColumnsTheH2SchemaHas() throws Exception {
        Flyway.configure().dataSource(H2_JDBC_URL, "sa", "").locations("db/migration/h2").load().migrate();

        List<Class<?>> entities = h2Entities();
        assertFalse(entities.isEmpty(), "no H2 entities in " + MIRROR_SAMPLE.getPackageName());

        try (Connection connection = DriverManager.getConnection(H2_JDBC_URL, "sa", "")) {
            Map<String, Set<String>> schema = columnsOf(connection);

            Map<String, Set<String>> missing = new TreeMap<>();
            for (Class<?> entity : entities) {
                String table = entity.getAnnotation(Table.class).name();
                Set<String> absent = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String column : mappedColumns(entity)) {
                    if (!schema.getOrDefault(table, Set.of()).contains(column)) {
                        absent.add(column);
                    }
                }
                if (!absent.isEmpty()) {
                    missing.put(table, absent);
                }
            }

            assertEquals(Map.of(), missing, "columns the H2 entities map but the H2 schema does not declare");
        }
    }

    private static Map<String, Set<String>> columnsOf(Connection connection) throws SQLException {
        Map<String, Set<String>> columns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, "%", "%")) {
            while (rows.next()) {
                columns.computeIfAbsent(rows.getString("TABLE_NAME"), table -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                        .add(rows.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static List<Class<?>> h2Entities() throws Exception {
        Path packageLocation = Path.of(MIRROR_SAMPLE.getResource("Database.class").toURI()).getParent();
        List<Path> classFiles;
        try (var files = Files.list(packageLocation)) {
            classFiles = files.filter(file -> file.getFileName().toString().endsWith(".class")).toList();
        }

        List<Class<?>> entities = new ArrayList<>();
        for (Path classFile : classFiles) {
            String simpleName = classFile.getFileName().toString().replace(".class", "");
            if (simpleName.contains("$")) {
                continue;
            }
            Class<?> candidate = Class.forName(MIRROR_SAMPLE.getPackageName() + "." + simpleName);
            if (candidate.isAnnotationPresent(Entity.class)) {
                entities.add(candidate);
            }
        }

        assertFalse(entities.isEmpty(), "no H2 entities found in " + packageLocation);
        return entities;
    }

    /**
     * Columns of the table the entity is mapped to: collections are in their own table, and a to-one association is a
     * foreign key named by {@code @JoinColumn} or by the Hibernate default {@code <field>_id}.
     */
    private static Set<String> mappedColumns(Class<?> entity) {
        Set<String> columns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Class<?> current = entity; current != Object.class; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                        || field.isAnnotationPresent(Transient.class)
                        || field.isAnnotationPresent(OneToMany.class)
                        || field.isAnnotationPresent(ManyToMany.class)
                        || field.isAnnotationPresent(ElementCollection.class)) {
                    continue;
                }
                if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                    columns.add(nameOrDefault(joinColumn == null ? null : joinColumn.name(), field.getName() + "_id"));
                } else {
                    Column column = field.getAnnotation(Column.class);
                    columns.add(nameOrDefault(column == null ? null : column.name(), field.getName()));
                }
            }
        }
        return columns;
    }

    private static String nameOrDefault(String name, String fieldName) {
        return name == null || name.isEmpty() ? fieldName : name;
    }
}
