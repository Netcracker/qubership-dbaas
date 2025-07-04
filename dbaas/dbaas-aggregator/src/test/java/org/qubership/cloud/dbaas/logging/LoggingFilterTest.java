package org.qubership.cloud.dbaas.logging;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import io.quarkus.bootstrap.logging.QuarkusDelayedHandler;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.handlers.ConsoleHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
public class LoggingFilterTest {

    private static final String POSTGRESQL = "postgresql";

    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;

    @AfterEach
    @Transactional
    public void setUp() {
        logicalDbDbaasRepository.getDatabaseDbaasRepository().deleteAll(logicalDbDbaasRepository.getDatabaseDbaasRepository().findAnyLogDbTypeByNamespace("test-namespace"));
    }

    @Test
    @Transactional
    void testLogMessageIsFiltered() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        QuarkusDelayedHandler handler = (QuarkusDelayedHandler) LogContext.getLogContext().getLogger("").getHandlers()[0];
        ConsoleHandler consoleHandler = (ConsoleHandler) Arrays.stream(handler.getHandlers()).filter(h -> h.getClass().equals(ConsoleHandler.class)).findFirst().get();
        try {
            consoleHandler.setOutputStream(baos);
            try {
                DatabaseRegistry database = createDatabase();
                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(database);
                DatabaseRegistry sameDatabase = createDatabase();
                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(sameDatabase);
            } catch (ConstraintViolationException e) {
                assertTrue(e.getMessage().contains("duplicate key value violates unique constraint \"database_registry_classifier_and_type_index\""));
                assertFalse(baos.toString().contains("duplicate key value violates unique constraint \"database_registry_classifier_and_type_index\""));
            }
        } finally {
            consoleHandler.setTarget(ConsoleHandler.Target.SYSTEM_OUT);
        }
    }

    private DatabaseRegistry createDatabase() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("test-key", "test-val");
        classifier.put("namespace", "test-namespace");
        classifier.put("microserviceName", "test-service");
        classifier.put("scope", "service");

        Database db = new Database();
        db.setId(UUID.randomUUID());
        db.setClassifier(classifier);

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        db.setDatabaseRegistry(List.of(databaseRegistry));
        databaseRegistry.setDatabase(db);

        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace("test-namespace");
        databaseRegistry.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put("username", "user");
            put(ROLE, Role.ADMIN.toString());
        }}));

        DbResource resource = new DbResource("someKind", "someName");
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        databaseRegistry.setResources(resources);
        databaseRegistry.setName("exact-classifier-match-test-db");
        databaseRegistry.setAdapterId("mongoAdapter");
        databaseRegistry.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        return databaseRegistry;
    }
}