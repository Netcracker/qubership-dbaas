package com.netcracker.it.dbaas.test;

import com.netcracker.it.dbaas.entity.CleanupMarkedForDropRequest;
import com.netcracker.it.dbaas.entity.DatabaseCreateRequestV3;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class NoNamespaceAPIV3IT extends AbstractIT {
    private static final String SECOND_TEST_NAMESPACE = TEST_NAMESPACE + "_2";

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), SECOND_TEST_NAMESPACE);
    }

    @Test
    void findLogicalDbByNameIT() throws IOException {
        DatabaseCreateRequestV3 databaseCreateRequest = DatabaseCreateRequestV3
                .builder(new ClassifierBuilder().build(), POSTGRES_TYPE)
                .originService(TEST_MICROSERVICE_NAME)
                .build();

        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), databaseCreateRequest, 201);
        String dbName = database.getName();

        List<DatabaseV3> databases = findByOnlyDbName(dbName);
        assertThat(databases, hasSize(1));

        List<DatabaseV3> databasesWithNamespaces = findByDbNameAndNamespace(dbName, TEST_NAMESPACE);
        assertThat(databasesWithNamespaces, hasSize(1));

        List<DatabaseV3> databasesWithNamespacesAndDecryptedPassword = findByDbNameAndNamespaceWithDecryptedPassword(dbName, TEST_NAMESPACE);
        assertThat(databasesWithNamespacesAndDecryptedPassword, hasSize(1));
        assertNotNull(databasesWithNamespacesAndDecryptedPassword.getFirst().getConnectionProperties().getFirst().get("password"));
    }

    @Test
    void getMarkedForDrop() throws IOException {
        DatabaseResponse db1 = helperV3.createDatabase(new ClassifierBuilder().ms("ms1").ns(TEST_NAMESPACE).markedForDrop().build(), POSTGRES_TYPE, 201);
        DatabaseResponse db2 = helperV3.createDatabase(new ClassifierBuilder().ms("ms2").ns(TEST_NAMESPACE).build(), POSTGRES_TYPE, 201);
        DatabaseResponse db3 = helperV3.createDatabase(new ClassifierBuilder().ms("ms3").ns(SECOND_TEST_NAMESPACE).markedForDrop().build(), POSTGRES_TYPE, 201);
        List<DatabaseV3> markedForDrop = helperV3.getMarkedForDrop(List.of(TEST_NAMESPACE, SECOND_TEST_NAMESPACE, "emptyNamespace"));
        assertEquals(2, markedForDrop.size());
        assertTrue(markedForDrop.stream().anyMatch(db -> db.getId().equals(db1.getId())));
        assertFalse(markedForDrop.stream().anyMatch(db -> db.getId().equals(db2.getId())));
        assertTrue(markedForDrop.stream().anyMatch(db -> db.getId().equals(db3.getId())));
    }

    @Test
    void cleanupMarkedForDrop() throws IOException {
        DatabaseResponse db1 = helperV3.createDatabase(new ClassifierBuilder().ms("ms1").ns(TEST_NAMESPACE).markedForDrop().build(), POSTGRES_TYPE, 201);
        DatabaseResponse db2 = helperV3.createDatabase(new ClassifierBuilder().ms("ms2").ns(TEST_NAMESPACE).build(), POSTGRES_TYPE, 201);
        DatabaseResponse db3 = helperV3.createDatabase(new ClassifierBuilder().ms("ms3").ns(SECOND_TEST_NAMESPACE).markedForDrop().build(), POSTGRES_TYPE, 201);
        CleanupMarkedForDropRequest cleanupRequest = new CleanupMarkedForDropRequest();
        cleanupRequest.setNamespaces(List.of(TEST_NAMESPACE, SECOND_TEST_NAMESPACE, "emptyNamespace"));
        cleanupRequest.setForce(false);
        cleanupRequest.setDelete(false);

        List<DatabaseV3> markedForDrop = helperV3.cleanupMarkedForDrop(cleanupRequest, 200);
        assertEquals(2, markedForDrop.size());
        assertTrue(markedForDrop.stream().anyMatch(db -> db.getId().equals(db1.getId())));
        assertTrue(markedForDrop.stream().noneMatch(db -> db.getId().equals(db2.getId())));
        assertTrue(markedForDrop.stream().anyMatch(db -> db.getId().equals(db3.getId())));
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db1.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db2.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db3.getClassifier(), SECOND_TEST_NAMESPACE, POSTGRES_TYPE, 200);

        cleanupRequest.setDelete(true);
        markedForDrop = helperV3.cleanupMarkedForDrop(cleanupRequest, 202);
        assertEquals(2, markedForDrop.size());
        assertTrue(markedForDrop.stream().anyMatch(db -> db.getId().equals(db1.getId())));
        assertTrue(markedForDrop.stream().noneMatch(db -> db.getId().equals(db2.getId())));
        assertTrue(markedForDrop.stream().anyMatch(db -> db.getId().equals(db3.getId())));
        Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(2)).withDelay(Duration.ofSeconds(1))).run(() -> {
            helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db1.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 404);
            helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db3.getClassifier(), SECOND_TEST_NAMESPACE, POSTGRES_TYPE, 404);
            helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db2.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        });
    }

    private List<DatabaseV3> findByOnlyDbName(String dbName) {
        return helperV3.findDatabasesByLogicalName(dbName, null);
    }

    private List<DatabaseV3> findByDbNameAndNamespace(String dbName, String namespace) {
        return helperV3.findDatabasesByLogicalName(dbName, namespace);
    }

    private List<DatabaseV3> findByDbNameAndNamespaceWithDecryptedPassword(String dbName, String namespace) {
        return helperV3.findDatabasesByLogicalName(dbName, namespace, true);
    }

}
