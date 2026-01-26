package com.netcracker.it.dbaas.test.db;

import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
class OpensearchIT extends AbstractIT {

    @BeforeEach
    void initHelper() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(OPENSEARCH_TYPE));
        cleanupDbs();
    }

    @AfterEach
    void cleanupDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    void testCreateCustomPrefixAndDelete() throws IOException {
        Map<String, Object> settingsMap = Collections.singletonMap("resourcePrefix", true);
        DatabaseResponse createdDatabase = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "opensearch-test", 201, OPENSEARCH_TYPE, null, TEST_NAMESPACE, true, settingsMap);
        log.debug("createdDatabase = {}", createdDatabase);
        String username = createdDatabase.getConnectionPropertyAsString("username");
        assertTrue(username.startsWith("dbaas_autotests"));
        helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    void testCreateGeneratedPrefixAndDelete() throws IOException {
        Map<String, Object> settingsMap = Collections.singletonMap("resourcePrefix", true);
        DatabaseResponse createdDatabase = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "opensearch-test", 201, OPENSEARCH_TYPE, null, TEST_NAMESPACE, true, null, "", settingsMap);
        log.debug("createdDatabase = {}", createdDatabase);
        String dbaasPrefix = createdDatabase.getConnectionPropertyAsString("resourcePrefix");
        String username = createdDatabase.getConnectionPropertyAsString("username");
        assertTrue(username.startsWith(dbaasPrefix));
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    void testCreateDefaultPrefixAndDelete() throws IOException {
        Map<String, Object> settingsMap = Collections.singletonMap("resourcePrefix", true);
        DatabaseResponse createdDatabase = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "opensearch-test", 201, OPENSEARCH_TYPE, null, TEST_NAMESPACE, true, null, "", settingsMap);
        log.debug("createdDatabase = {}", createdDatabase);
        String username = createdDatabase.getConnectionPropertyAsString("username");
        assertNotNull(username);
        helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }
}
