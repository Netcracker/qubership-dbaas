package com.netcracker.it.dbaas.test.db;

import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@Tag("opensearch")
class OpensearchIT extends AbstractIT {

    @BeforeEach
    void initHelper() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(OPENSEARCH_TYPE));
        cleanupDbs();
    }

    @AfterEach
    void cleanupDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteAllLogicalDatabasesAndNamespaceBackupsInTestNamespaces();
    }

    @Test
    void testCreateCustomPrefixAndDelete() throws IOException {
        var namespace = helperV3.generateTestNamespace();
        Map<String, Object> settingsMap = Collections.singletonMap("resourcePrefix", true);
        DatabaseResponse createdDatabase = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "opensearch-test", 201, OPENSEARCH_TYPE, null, namespace, true, null, namespace, settingsMap);
        log.debug("createdDatabase = {}", createdDatabase);
        String dbaasPrefix = createdDatabase.getConnectionPropertyAsString("resourcePrefix");
        assertTrue(dbaasPrefix.startsWith(namespace), "namespace should be used as a prefix");
        String username = createdDatabase.getConnectionPropertyAsString("username");
        assertTrue(username.startsWith(namespace), "resource name should start with custom prefix");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), namespace);
    }

    @Test
    void testCreateGeneratedPrefixAndDelete() throws IOException {
        var namespace = helperV3.generateTestNamespace();
        Map<String, Object> settingsMap = Collections.singletonMap("resourcePrefix", true);
        DatabaseResponse createdDatabase = helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "opensearch-test", 201, OPENSEARCH_TYPE, null, namespace, true, null, null, settingsMap);
        log.debug("createdDatabase = {}", createdDatabase);
        String dbaasPrefix = createdDatabase.getConnectionPropertyAsString("resourcePrefix");
        String username = createdDatabase.getConnectionPropertyAsString("username");
        assertTrue(username.startsWith(dbaasPrefix));
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), namespace);
    }
}
