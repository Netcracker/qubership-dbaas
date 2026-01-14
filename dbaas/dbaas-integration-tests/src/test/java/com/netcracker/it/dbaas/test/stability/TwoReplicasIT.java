package com.netcracker.it.dbaas.test.stability;

import com.netcracker.cloud.junit.cloudcore.extension.service.PodPortForwardParams;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import com.netcracker.it.dbaas.helpers.PaasHelper;
import com.netcracker.it.dbaas.test.AbstractIT;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.util.List;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class TwoReplicasIT extends AbstractIT {

    private final static int TARGET_NUMBER_OF_REPLICAS = 2;
    private static Integer DEFAULT_NUMBER_OF_REPLICAS;

    static List<Pod> podList;
    static PaasHelper paasHelper;

    @BeforeAll
    public void setUpEnvironment() {
        paasHelper = new PaasHelper(kubernetesClient);
        DEFAULT_NUMBER_OF_REPLICAS = paasHelper.replicasNumber();
        assertTrue(DEFAULT_NUMBER_OF_REPLICAS > 0, "replicas number must be more than 0");
        paasHelper.scaleDbaas(TARGET_NUMBER_OF_REPLICAS);
        podList = kubernetesClient.pods().withLabel("name", DBAAS_SERVICE_NAME).list().getItems();
    }

    @AfterAll
    public void cleanUpEnvironment() {
        if (DEFAULT_NUMBER_OF_REPLICAS != null) {
            paasHelper.scaleDbaas(DEFAULT_NUMBER_OF_REPLICAS);
        }
    }

    @AfterEach
    public void cleanUp() throws IOException {
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    public void testNumberOfReplicas() {
        assertThat(podList, hasSize(TARGET_NUMBER_OF_REPLICAS));
    }

    @Test
    public void createDatabaseTwoTimes() throws SQLException, IOException {
        DbaasHelperV3 helperPod1 = createDbaasHelper(podList.getFirst());
        String authorization = helperPod1.getClusterDbaAuthorization();
        log.info("Create First database");
        DatabaseResponse db1 = helperPod1.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE,
                null, TEST_NAMESPACE, false);
        assertEquals(TEST_NAMESPACE, db1.getNamespace());
        log.info("Check connection to created database");
        helperPod1.checkConnectionPostgres(db1);

        //do same for pod 2
        DbaasHelperV3 helperPod2 = createDbaasHelper(podList.get(1));
        DatabaseResponse db2 = helperPod2.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE,
                null, TEST_NAMESPACE, false);
        assertEquals(TEST_NAMESPACE, db2.getNamespace());
        log.info("Check connection to database");
        helperPod1.checkConnectionPostgres(db2);

        assertEquals(db1.getConnectionProperties(), db2.getConnectionProperties());
    }

    @Test
    public void createAndDeleteDatabaseInDifferentPods() throws SQLException, IOException, InterruptedException {
        DbaasHelperV3 helperPod1 = createDbaasHelper(podList.getFirst());
        String authorization = helperPod1.getClusterDbaAuthorization();
        log.info("Create First database");
        DatabaseResponse db1 = helperPod1.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE,
                null, TEST_NAMESPACE, false);
        assertEquals(TEST_NAMESPACE, db1.getNamespace());
        log.info("Check connection to created database");
        helperPod1.checkConnectionPostgres(db1);

        //do for pod 2
        DbaasHelperV3 helperPod2 = createDbaasHelper(podList.get(1));
        helperPod2.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);

        log.info("Sleep for 2 seconds. Waiting for database deletion");
        Thread.sleep(2000);

        List<DatabaseV3> databasesFromPod1 = helperPod1.getDatabasesByNamespace(TEST_NAMESPACE);
        List<DatabaseV3> databasesFromPod2 = helperPod2.getDatabasesByNamespace(TEST_NAMESPACE);

        assertThat(databasesFromPod1, is(empty()));
        assertThat(databasesFromPod2, is(empty()));
    }

    private DbaasHelperV3 createDbaasHelper(Pod pod) {
        Service service = kubernetesClient.services().withName(DBAAS_SERVICE_NAME).get();
        int targetPort = service.getSpec().getPorts().getFirst().getTargetPort().getIntVal();
        URL portForward = portForwardService.portForward(PodPortForwardParams.builder(pod.getMetadata().getName(), targetPort).build()).toHttpUrl();
        return new DbaasHelperV3(portForward, kubernetesClient, readDbaasUsersFromSecret(), portForwardService);
    }
}
