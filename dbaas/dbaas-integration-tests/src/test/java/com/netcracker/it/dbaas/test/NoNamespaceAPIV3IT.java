package com.netcracker.it.dbaas.test;

import com.netcracker.it.dbaas.entity.DatabaseCreateRequestV3;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

@Slf4j
public class NoNamespaceAPIV3IT extends AbstractIT {

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
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
        Assertions.assertNotNull(databasesWithNamespacesAndDecryptedPassword.getFirst().getConnectionProperties().getFirst().get("password"));
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
