package com.netcracker.cloud.dbaas.entity;

import com.netcracker.cloud.dbaas.dto.ConnectionDescription;
import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;
import com.netcracker.cloud.dbaas.utils.DatabaseBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The H2 entities mirror the PostgreSQL tables they are loaded from, and both sides extend one mapped superclass per
 * table. These tests keep the mirror in step with PostgreSQL: a field that lands on one side only, or one that a
 * mirror copy loses, fails here instead of surfacing as a null on a read served from the H2 cache.
 */
class H2MirrorParityTest {

    /**
     * Fields a PostgreSQL entity carries without an H2 counterpart. A new name here needs a column in
     * {@code db/migration/h2} or a reason the mirror must not carry it.
     */
    private static final Set<String> POSTGRES_ONLY_FIELDS = Set.of("lastRotatedAt");

    /**
     * Relations are compared through the entity they point at, so the parent of a PostgreSQL entity is never compared
     * against the parent of its mirror.
     */
    private static final Set<String> RELATION_FIELDS =
            Set.of("databaseRegistry", "resources", "dbState", "adapter", "database");

    @Test
    void testAsH2Entity_CopiesEveryDatabaseField() {
        final Database source = database();

        final var copy = source.asH2Entity();

        List<Executable> checks = new ArrayList<>(mirrorChecks(source, copy));
        checks.addAll(mirrorChecks(source.getDbState(), copy.getDbState()));
        checks.addAll(relationChecks(source.getResources(), copy.getResources(), "resources"));
        checks.addAll(relationChecks(source.getDatabaseRegistry(), copy.getDatabaseRegistry(), "databaseRegistry"));
        checks.add(() -> assertSame(copy, copy.getDatabaseRegistry().get(0).getDatabase()));

        assertAll(checks);
    }

    @Test
    void testAsH2Entity_CopiesEveryDatabaseRegistryField() {
        final DatabaseRegistry source = database().getDatabaseRegistry().get(0);
        final com.netcracker.cloud.dbaas.entity.h2.Database parent =
                new com.netcracker.cloud.dbaas.entity.h2.Database();

        final var copy = source.asH2Entity(parent);

        List<Executable> checks = new ArrayList<>(mirrorChecks(source, copy));
        checks.add(() -> assertSame(parent, copy.getDatabase()));

        assertAll(checks);
    }

    @Test
    void testAsH2Entity_CopiesEveryDbResourceField() {
        final DbResource source = new DbResource(DbResource.DATABASE_KIND, "test-db");

        assertAll(mirrorChecks(source, source.asH2Entity()));
    }

    @Test
    void testAsH2Entity_CopiesEveryDbStateField() {
        final DbState source = dbState();

        assertAll(mirrorChecks(source, source.asH2Entity()));
    }

    @Test
    void testAsH2Entity_CopiesEveryAdapterRegistrationEntryField() {
        final ExternalAdapterRegistrationEntry source = adapterRegistrationEntry();

        assertAll(mirrorChecks(source, source.asH2Entity()));
    }

    @Test
    void testAsH2Entity_CopiesEveryPhysicalDatabaseField() {
        final PhysicalDatabase source = physicalDatabase();

        final var copy = source.asH2Entity();

        List<Executable> checks = new ArrayList<>(mirrorChecks(source, copy));
        checks.addAll(mirrorChecks(source.getAdapter(), copy.getAdapter()));

        assertAll(checks);
    }

    @Test
    void testH2MirrorEntities_DeclareEveryPostgresField() {
        assertAll(
                () -> assertEquals(persistentFieldNames(Database.class),
                        persistentFieldNames(com.netcracker.cloud.dbaas.entity.h2.Database.class),
                        "entity/pg.Database and entity/h2.Database fields"),
                () -> assertEquals(persistentFieldNames(DatabaseRegistry.class),
                        persistentFieldNames(com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry.class),
                        "entity/pg.DatabaseRegistry and entity/h2.DatabaseRegistry fields"),
                () -> assertEquals(persistentFieldNames(DbResource.class),
                        persistentFieldNames(com.netcracker.cloud.dbaas.entity.h2.DbResource.class),
                        "entity/pg.DbResource and entity/h2.DbResource fields"),
                () -> assertEquals(persistentFieldNames(DbState.class),
                        persistentFieldNames(com.netcracker.cloud.dbaas.entity.h2.DbState.class),
                        "entity/pg.DbState and entity/h2.DbState fields"),
                () -> assertEquals(persistentFieldNames(ExternalAdapterRegistrationEntry.class),
                        persistentFieldNames(com.netcracker.cloud.dbaas.entity.h2.ExternalAdapterRegistrationEntry.class),
                        "entity/pg.ExternalAdapterRegistrationEntry and its H2 mirror fields"),
                () -> assertEquals(persistentFieldNames(PhysicalDatabase.class),
                        persistentFieldNames(com.netcracker.cloud.dbaas.entity.h2.PhysicalDatabase.class),
                        "entity/pg.PhysicalDatabase and entity/h2.PhysicalDatabase fields"));
    }

    private static Database database() {
        Database database = new DatabaseBuilder().registry().build();
        database.setOldClassifier(new TreeMap<>(Map.of("dbClassifier", "test")));
        database.setMarkedForDrop(true);
        database.setTimeDbCreation(Date.from(Instant.EPOCH));
        database.setBackupDisabled(true);
        database.setDbOwnerRoles(List.of("owner-role"));
        database.setSettings(new TreeMap<>(Map.of("instanceClass", "db.m5.large")));
        database.setConnectionDescription(ConnectionDescription.DEFAULT_CONNECTION_DESCRIPTION);
        database.setWarnings(List.of("deprecated classifier keys"));
        database.setExternallyManageable(true);
        database.setBgVersion("2");
        database.setDbState(dbState());
        database.getDatabaseRegistry().forEach(registry -> registry.setTimeDbCreation(Date.from(Instant.EPOCH)));
        return database;
    }

    private static DbState dbState() {
        DbState dbState = new DbState(AbstractDbState.DatabaseStateStatus.CREATED, "pod-1");
        dbState.setDescription("created by the operator");
        return dbState;
    }

    private static ExternalAdapterRegistrationEntry adapterRegistrationEntry() {
        return new ExternalAdapterRegistrationEntry("adapter-1", "http://adapter:8080",
                new HttpBasicCredentials("adapter-user", "adapter-password"), "1.0", new ApiVersion(List.of()));
    }

    private static PhysicalDatabase physicalDatabase() {
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId("physical-database-1");
        physicalDatabase.setPhysicalDatabaseIdentifier("postgres-adapter");
        physicalDatabase.setGlobal(true);
        physicalDatabase.setLabels(Map.of("region", "eu-central-1"));
        physicalDatabase.setType("postgresql");
        physicalDatabase.setRegistrationDate(Date.from(Instant.EPOCH));
        physicalDatabase.setRoles(List.of("admin"));
        physicalDatabase.setFeatures(Map.of("backup", true));
        physicalDatabase.setUnidentified(true);
        physicalDatabase.setRoHost("ro.postgres:5432");
        physicalDatabase.setAdapter(adapterRegistrationEntry());
        return physicalDatabase;
    }

    /**
     * One check per mapped field, so a copy that loses a field reports that field by name, plus the fields the fixture
     * left unset, which would hide such a loss.
     */
    private static List<Executable> mirrorChecks(Object source, Object copy) {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(Set.of(), unsetFields(source),
                source.getClass().getSimpleName() + " fields left unset"));
        for (Field field : copiedFields(source.getClass())) {
            checks.add(() -> assertEquals(read(field, source), read(field, copy), field.getName()));
        }
        return checks;
    }

    private static List<Executable> relationChecks(List<?> source, List<?> copy, String relation) {
        assertEquals(source.size(), copy.size(), relation + " count");
        List<Executable> checks = new ArrayList<>();
        for (int position = 0; position < source.size(); position++) {
            checks.addAll(mirrorChecks(source.get(position), copy.get(position)));
        }
        return checks;
    }

    private static List<Field> copiedFields(Class<?> entity) {
        List<Field> fields = new ArrayList<>();
        for (Field field : persistentFields(entity)) {
            if (!RELATION_FIELDS.contains(field.getName())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static Set<String> persistentFieldNames(Class<?> entity) {
        Set<String> names = new LinkedHashSet<>();
        for (Field field : persistentFields(entity)) {
            names.add(field.getName());
        }
        return names;
    }

    private static Set<String> unsetFields(Object entity) {
        Set<String> unset = new LinkedHashSet<>();
        for (Field field : persistentFields(entity.getClass())) {
            if (!field.getType().isPrimitive() && read(field, entity) == null) {
                unset.add(field.getName());
            }
        }
        return unset;
    }

    private static List<Field> persistentFields(Class<?> entity) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = entity; current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())
                        && !POSTGRES_ONLY_FIELDS.contains(field.getName())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new AssertionError("Cannot read " + field.getName() + " of " + owner.getClass().getSimpleName(), e);
        }
    }
}
