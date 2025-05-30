package org.qubership.cloud.dbaas.integration.stability;

import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2PhysicalDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
@TestProfile(DbaasPhysicalDatabasePgConnectivityFailureTest.DirtiesContextProfile.class)
class DbaasPhysicalDatabasePgConnectivityFailureTest {

    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @Inject
    H2PhysicalDatabaseRepository h2PhysicalDatabaseRepository;
    @Inject
    PhysicalDatabasesRepository physicalDatabasesRepository;

    @BeforeEach
    public void setUp() {
        clean();
    }

    public void clean() {
        physicalDatabaseDbaasRepository.findByType("type-test").filter(Objects::nonNull).forEach(pd -> physicalDatabaseDbaasRepository.delete(pd));
    }

    @Test
    void testExactClassifierMatch() {
        PhysicalDatabase physicalDatabase = createPhysicalDatabase();
        QuarkusTransaction.requiringNew().run(() -> physicalDatabaseDbaasRepository.save(physicalDatabase));
        PostgresqlContainerResource.postgresql.stop();
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2PhysicalDatabaseRepository.findByIdOptional(physicalDatabase.getId()).isPresent());
        boolean exceptionHappened = false;
        try {
            QuarkusTransaction.requiringNew().run(() -> physicalDatabasesRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier()));
        } catch (Exception exception) {
            exceptionHappened = true;
        }
        assertTrue(exceptionHappened);

        PhysicalDatabase foundDb = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());
        assertNotNull(foundDb);
        assertEquals(foundDb, physicalDatabase);
    }


    private PhysicalDatabase createPhysicalDatabase() {
        PhysicalDatabase db = new PhysicalDatabase();
        db.setId(UUID.randomUUID().toString());
        db.setPhysicalDatabaseIdentifier(UUID.randomUUID().toString());
        db.setType("type-test");
        return db;
    }

    @NoArgsConstructor
    protected static final class DirtiesContextProfile implements QuarkusTestProfile {
    }
}
