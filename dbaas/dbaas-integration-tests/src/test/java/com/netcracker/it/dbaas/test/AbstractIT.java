package com.netcracker.it.dbaas.test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.Cloud;
import com.netcracker.cloud.junit.cloudcore.extension.annotations.EnableExtension;
import com.netcracker.cloud.junit.cloudcore.extension.service.Endpoint;
import com.netcracker.cloud.junit.cloudcore.extension.service.PortForwardService;
import com.netcracker.cloud.junit.cloudcore.extension.service.ServicePortForwardParams;
import com.netcracker.it.dbaas.entity.AggregatorHealth;
import com.netcracker.it.dbaas.entity.DatabaseV3;
import com.netcracker.it.dbaas.entity.DbaasUsersData;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for all the DBaaS Aggregator ITs.
 */
@Slf4j
@EnableExtension
public abstract class AbstractIT {
    public static final String DBAAS_SERVICE_NAME = "dbaas-aggregator";
    public static final int HTTP_PORT = 8080;
    public static final int HTTPS_PORT = 8443;

    public static final String MONGODB_TYPE = "mongodb";
    public static final String POSTGRES_TYPE = "postgresql";
    public static final String REDIS_TYPE = "redis";
    public static final String CASSANDRA_TYPE = "cassandra";
    public static final String OPENSEARCH_TYPE = "opensearch";
    public static final String CLICKHOUSE_TYPE = "clickhouse";
    public static final String ARANGODB_TYPE = "arangodb";
    public static final String[] ALL_DB_TYPES =
            new String[]{POSTGRES_TYPE, MONGODB_TYPE, REDIS_TYPE, CASSANDRA_TYPE, OPENSEARCH_TYPE, CLICKHOUSE_TYPE, ARANGODB_TYPE};


    protected static final Type AGGREGATOR_HEALTH_TYPE = new TypeToken<AggregatorHealth>() {
    }.getType();

    public final static RetryPolicy<Object> DEFAULT_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(5)).withMaxDuration(Duration.ofMinutes(1));

    protected static Pod pod;

    protected static URL dbaasServiceUrl;

    @Cloud
    protected static KubernetesClient kubernetesClient;

    @Cloud
    protected static PortForwardService portForwardService;

    protected static DbaasHelperV3 helperV3;

    protected static DbaasUsersData dbaasUsers;

    protected final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    protected static final Gson gson = new Gson();

    @BeforeAll
    public static void setUpDbaasHelper(TestInfo testInfo) {
        DbaasHelperV3.regenerateRequestId();
        pod = kubernetesClient.pods().withLabel("name", DBAAS_SERVICE_NAME).list().getItems().getFirst();
        log.info("Preparation for test class {} started", testInfo.getTestClass().orElse(AbstractIT.class).getSimpleName());
        dbaasServiceUrl = createDbaasUrl(portForwardService);
        dbaasUsers = readDbaasUsersFromSecret();
        helperV3 = createDbaasHelperV3(dbaasServiceUrl, dbaasUsers);
    }

    @AfterAll
    public static void generalCleanUp(TestInfo testInfo) {
        DbaasHelperV3.regenerateRequestId();
        log.info("Clean up for test class {} started", testInfo.getTestClass().orElse(AbstractIT.class).getSimpleName());
        portForwardService.closePortForward(new Endpoint(dbaasServiceUrl.getHost(), dbaasServiceUrl.getPort()));
    }

    @BeforeEach
    public void parentBeforeEach(TestInfo testInfo) {
        DbaasHelperV3.regenerateRequestId();
        log.info("Test started: {}#{}", testInfo.getTestClass().orElse(this.getClass()).getSimpleName(), testInfo.getDisplayName());
    }

    @AfterEach
    public void parentAfterEach(TestInfo testInfo) {
        closePortForwardAfterTest();
        log.info("Test finished: {}#{}", testInfo.getTestClass().orElse(this.getClass()).getSimpleName(), testInfo.getDisplayName());
        DbaasHelperV3.regenerateRequestId();
    }

    protected void closePortForwardAfterTest() {
        helperV3.closePortForwardConnections();
    }

    protected static DbaasHelperV3 createDbaasHelperV3(URL dbaasServiceUrl, DbaasUsersData dbaasUsers) {
        log.info("Initializing DbaasHelper V3.");
        return new DbaasHelperV3(dbaasServiceUrl, kubernetesClient, dbaasUsers, portForwardService);
    }

    protected static URL createDbaasUrl(PortForwardService portForwardService) {
        return portForwardService.portForward(ServicePortForwardParams.builder(DBAAS_SERVICE_NAME, HTTP_PORT).build()).toHttpUrl();
    }

    public static boolean isTLSEnabledInDbaas() {
        return DbaasHelperV3.readEnvVariable(pod, "INTERNAL_TLS_ENABLED").
                map(Boolean::parseBoolean).orElse(false);
    }

    public static String getPropertyOrEnv(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            value = System.getenv(name);
            if (value == null) {
                value = System.getenv("ENV_" + name);
            }
        }
        return value;
    }

    protected static DbaasUsersData readDbaasUsersFromSecret() {
        Optional<Volume> optional = pod.getSpec().getVolumes().stream()
                .filter(volume -> volume.getName().equals("dbaas-security-configuration-volume"))
                .findAny();
        assertTrue(optional.isPresent());
        String secretName = optional.get().getSecret().getSecretName();
        Secret secret = kubernetesClient.secrets().withName(secretName).get();


        String usersJson;
        if (secret.getData().size() > 1) {
            usersJson = secret.getData().get("users.json");
        } else {
            usersJson = secret.getData().values().stream().findAny().get();
        }
        assertNotNull(usersJson);
        usersJson = new String(Base64.getDecoder().decode(usersJson), StandardCharsets.UTF_8);
        Type type = new TypeToken<Map<String, DbaasUsersData.UserData>>() {
        }.getType();
        return new DbaasUsersData(gson.fromJson(usersJson, type));
    }

    protected AggregatorHealth getHealth() throws IOException {
        Request request = new Request.Builder()
                .url(dbaasServiceUrl + "health")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            log.info("Response: {}", response);
            ResponseBody responseBody = response.body();
            assertNotNull(responseBody);
            String body = responseBody.string();
            log.debug("Response body: {}", body);
            assertEquals(200, response.code());
            return gson.fromJson(body, AGGREGATOR_HEALTH_TYPE);
        }
    }

    protected List<DatabaseV3> notMarkedForDrop(List<DatabaseV3> dbs) {
        return dbs.stream().filter(databaseV3 -> !databaseV3.getClassifier().containsKey("MARKED_FOR_DROP")).toList();
    }

    public Map<String, Object> migrateV2ClassifierToV3(Map<String, Object> classifier) {
        Map<String, Object> classifierV3 = new TreeMap<>(classifier);
        Object isService = classifierV3.get("isServiceDb");
        if (isService == null) {
            isService = classifierV3.get("isService");
        }
        classifierV3.remove("isService");
        classifierV3.remove("isServiceDb");

        if (isService == null) {
            classifierV3.put("scope", "tenant");
        } else {
            if (isService instanceof String) {
                classifierV3.put("scope", isService.equals("true") ? "service" : "tenant");
            }
            if (isService instanceof Boolean) {
                classifierV3.put("scope", (Boolean) isService ? "service" : "tenant");
            }
        }
        return classifierV3;
    }
}
