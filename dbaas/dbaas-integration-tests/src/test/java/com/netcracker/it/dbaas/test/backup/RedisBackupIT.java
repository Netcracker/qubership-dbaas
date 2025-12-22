package com.netcracker.it.dbaas.test.backup;

import com.netcracker.it.dbaas.entity.backup.v3.NamespaceBackupV3;
import com.netcracker.it.dbaas.helpers.BackupHelperV3;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@Tag("backup")
public class RedisBackupIT extends AbstractIT {

    public static final String SOURCE_NAMESPACE = TEST_NAMESPACE + "_source";
    public static final String TARGET_NAMESPACE = TEST_NAMESPACE + "_target";

    private BackupHelperV3 backupsHelper;

    @BeforeEach
    public void initHelper() throws IOException, InterruptedException {
        assumeTrue(helperV3.hasAdapterOfType(REDIS_TYPE));
        backupsHelper = new BackupHelperV3(dbaasServiceUrl, helperV3);
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException, InterruptedException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), SOURCE_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TARGET_NAMESPACE);
    }


    @Test
    public void testCollectWithoutIgnoreNotBackupableDatabases() throws IOException {
        log.info("check backup procedure without ignoreNotBackupableDatabases flag");
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "postgresql-backup-test", 201, "postgresql", null, TEST_NAMESPACE, false);
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "redis-backup-test", 201, "redis", null, TEST_NAMESPACE, false, null, "a-test", null);
        try {
            backupsHelper.collectBackup(helperV3.getBackupDaemonAuthorization(), TEST_NAMESPACE, false);
            fail();
        } catch (HttpClientErrorException ex) {
            assertEquals(501, ex.getStatusCode().value());
            assertThat(ex.getMessage(), containsString("The backup request have not been passed validate procedure"));
        }
    }

    @Test
    public void testCollectWithIgnoreNotBackupableDatabases() throws IOException {
        log.info("Check backup procedure with ignoreNotBackupableDatabases flag");
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "postgresql-backup-test", 201, "postgresql", null, TEST_NAMESPACE, false);
        helperV3.createDatabase(helperV3.getClusterDbaAuthorization(), "redis-backup-test", 201, "redis", null, TEST_NAMESPACE, false, null, "a-test", null);
        NamespaceBackupV3 backup = backupsHelper.collectBackup(helperV3.getBackupDaemonAuthorization(), TEST_NAMESPACE, true);
        log.info("backup {}", backup);
        assertNotNull(backup.getId());
        assertThat(backup.getBackups(), hasSize(1));
        assertThat(backup.getDatabases(), hasSize(1));
    }
}
