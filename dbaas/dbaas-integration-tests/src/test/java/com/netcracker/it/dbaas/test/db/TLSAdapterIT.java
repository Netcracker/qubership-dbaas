package com.netcracker.it.dbaas.test.db;

import com.netcracker.cloud.security.core.utils.tls.TlsUtils;
import com.netcracker.it.dbaas.entity.PhysicalDatabaseRegistrationResponseDTOV3;
import com.netcracker.it.dbaas.entity.RegisteredPhysicalDatabasesDTOV3;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class TLSAdapterIT extends AbstractIT {

    private final String HTTPS = "https";

    @BeforeEach
    public void initHelper() throws IOException, InterruptedException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException, InterruptedException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    public void testPostgresqlAdapterNonTLSConnection() throws IOException {
        OkHttpClient nonSecuredHttpClient = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        RegisteredPhysicalDatabasesDTOV3 physicalDatabases = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200);
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identified = physicalDatabases.getIdentified();
        for (PhysicalDatabaseRegistrationResponseDTOV3 value : identified.values()) {
            String adapterAddress = value.getAdapterAddress();
            if (!adapterAddress.contains(HTTPS)) {
                Call call = nonSecuredHttpClient.newCall(new Request.Builder()
                        .url(helperV3.createPortForward(adapterAddress).toString() + "health")
                        .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                        .build());
                try (Response response = call.execute()) {
                    String healthJson = response.body().string();
                    log.info("Adapter {} health: {}", adapterAddress, healthJson);
                    assertEquals(200, response.code());
                } catch (IOException e) {
                    log.debug("Error while checking adapter {} health:", adapterAddress, e);
                    fail("Postgresql adapter's health is not ok");
                }
            }
        }
    }

    @Test
    public void testPostgresqlAdapterTLSConnection() throws IOException {
        log.info("run testPostgresqlAdapterTLSConnection test");
        Optional<String> isIlsEnabledValue = DbaasHelperV3.readEnvVariable(pod, "INTERNAL_TLS_ENABLED");
        boolean isIlsEnabled = Boolean.parseBoolean(isIlsEnabledValue.orElse("false"));
        log.info("isIlsEnabled: {}", isIlsEnabled);
        assumeTrue(isIlsEnabled, "Skip test because TLS in aggregator is disabled");
        RegisteredPhysicalDatabasesDTOV3 physicalDatabases = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200);
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identified = physicalDatabases.getIdentified();
        OkHttpClient securedHttpClient = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .sslSocketFactory(TlsUtils.getSslContext().getSocketFactory(), TlsUtils.getTrustManager())
                .build();
        for (PhysicalDatabaseRegistrationResponseDTOV3 value : identified.values()) {
            String adapterAddress = value.getAdapterAddress();
            if (adapterAddress.contains(HTTPS)) {
                String url = helperV3.createPortForwardWithScheme(adapterAddress, "https").toString();
                Call call = securedHttpClient.newCall(new Request.Builder()
                        .addHeader("X-Request-Id", DbaasHelperV3.getRequestId())
                        .url(url + "health")
                        .build());
                try (Response response = call.execute()) {
                    String healthJson = response.body().string();
                    log.info("Adapter {} health: {}", adapterAddress, healthJson);
                    assertEquals(200, response.code());
                } catch (IOException e) {
                    log.debug("Error while checking adapter {} health:", adapterAddress, e);
                    fail("Postgresql adapter's health is not ok");
                }
            }
        }
    }

}
