package com.netcracker.it.dbaas.test.db;

import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class ClickhouseIT extends AbstractIT {
    @BeforeEach
    public void initHelper() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(CLICKHOUSE_TYPE));
        cleanupDbs();
    }

    @AfterEach
    public void cleanupDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    void testClickhouseConnectionPropertiesStruct() throws IOException {
        DatabaseResponse createdDatabase = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "clickhouse-test", 201, CLICKHOUSE_TYPE, null, TEST_NAMESPACE, true);
        log.debug("createdDatabase = {}", createdDatabase);
        Map<String, Object> classifier = new ClassifierBuilder().test("clickhouse-test").build();
        DatabaseResponse db = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, CLICKHOUSE_TYPE, 200);
        Map<String, Object> connectionProperties = db.getConnectionProperties();
        int numberOfProperties = 7;
        assertEquals(numberOfProperties, connectionProperties.size());
        assertTrue(connectionProperties.containsKey("url"));
        assertTrue(connectionProperties.containsKey("port"));
        assertTrue(connectionProperties.containsKey("host"));
        assertTrue(connectionProperties.containsKey("name"));
        assertTrue(connectionProperties.containsKey("username"));
        assertTrue(connectionProperties.containsKey("password"));
        assertTrue(connectionProperties.containsKey("role"));
        helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    void testClickhouseBasicOperations() throws SQLException, IOException {
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "clickhouse-test", 201, CLICKHOUSE_TYPE, null, TEST_NAMESPACE, true);
        Map<String, Object> classifier = new ClassifierBuilder().test("clickhouse-test").build();
        DatabaseResponse db = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, CLICKHOUSE_TYPE, 200);

        helperV3.checkConnectionClickhouse(db, "test", "test");
    }
}
