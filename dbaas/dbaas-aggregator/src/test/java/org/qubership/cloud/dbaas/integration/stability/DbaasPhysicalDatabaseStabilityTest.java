package org.qubership.cloud.dbaas.integration.stability;

import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.h2.H2PhysicalDatabaseRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabasesRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@Slf4j
class DbaasPhysicalDatabaseStabilityTest {

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

    @AfterEach
    public void tearDown() {
        clean();
    }

    @Transactional
    public void clean() {
        physicalDatabaseDbaasRepository.findByType("type-test").filter(Objects::nonNull).forEach(pd -> physicalDatabaseDbaasRepository.delete(pd));
    }

    @Test
    void testSameDatabaseInH2() {
        PhysicalDatabase physicalDatabase = createPhysicalDatabase();
        QuarkusTransaction.requiringNew().run(() -> physicalDatabaseDbaasRepository.save(physicalDatabase));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2PhysicalDatabaseRepository.findByIdOptional(physicalDatabase.getId()).isPresent());

        PhysicalDatabase pgDatabase = physicalDatabasesRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());
        Optional<org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase> h2Database = h2PhysicalDatabaseRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());

        assertEquals(pgDatabase, h2Database.get().asPgEntity());
        h2PhysicalDatabaseRepository.getEntityManager().clear();
        QuarkusTransaction.requiringNew().run(() -> physicalDatabaseDbaasRepository.delete(physicalDatabase));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2PhysicalDatabaseRepository.findByIdOptional(physicalDatabase.getId()).isEmpty());

        pgDatabase = physicalDatabasesRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());
        h2Database = h2PhysicalDatabaseRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());
        assertNull(pgDatabase);
        assertTrue(h2Database.isEmpty());
    }

    @Test
    void testDatabaseEventH2() {
        PhysicalDatabase physicalDatabase = createPhysicalDatabase();
        QuarkusTransaction.requiringNew().run(() -> physicalDatabasesRepository.persist(physicalDatabase));
        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS).pollInSameThread()
                .until(() -> h2PhysicalDatabaseRepository.findByIdOptional(physicalDatabase.getId()).isPresent());

        PhysicalDatabase pgDatabase = physicalDatabasesRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());
        Optional<org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase> h2Database = h2PhysicalDatabaseRepository.findByPhysicalDatabaseIdentifier(physicalDatabase.getPhysicalDatabaseIdentifier());

        assertEquals(pgDatabase, h2Database.get().asPgEntity());
    }

    private PhysicalDatabase createPhysicalDatabase() {
        PhysicalDatabase db = new PhysicalDatabase();
        db.setId(UUID.randomUUID().toString());
        db.setPhysicalDatabaseIdentifier(UUID.randomUUID().toString());
        db.setType("type-test");
        return db;
    }
}
