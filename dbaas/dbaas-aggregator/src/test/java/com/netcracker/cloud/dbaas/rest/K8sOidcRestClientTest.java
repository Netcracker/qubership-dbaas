package com.netcracker.cloud.dbaas.rest;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.netcracker.cloud.dbaas.TestJwtUtils;

import java.io.IOException;

import static org.junit.Assert.*;

class K8sOidcRestClientTest {
    private static final String mockOidcConfig = "{\"issuer\":\"https://kubernetes.default.svc.cluster.local\","
            + "\"jwks_uri\":\"https://192.168.49.2:8443/openid/v1/jwks\","
            + "\"response_types_supported\":[\"id_token\"],"
            + "\"subject_types_supported\":[\"public\"],"
            + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";

    private final String mockJwks;

    private final K8sOidcRestClient restClient;

    K8sOidcRestClientTest() throws IOException, JoseException {
        mockJwks = new TestJwtUtils().getJwks();
        restClient = new K8sOidcRestClient(false, "");
    }

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void getOidcConfiguration() throws IOException, InterruptedException {
        MockWebServer server = new MockWebServer();

        MockResponse response = new MockResponse();
        response.setBody(mockOidcConfig);
        server.enqueue(response);

        server.start();

        HttpUrl baseUrl = server.url("");
        restClient.jwtIssuer = baseUrl.toString();

        assertEquals("https://192.168.49.2:8443/openid/v1/jwks", restClient.getOidcConfiguration().getJwks_uri());

        server.close();
    }

    @Test
    void getJwks() throws IOException {
        MockWebServer server = new MockWebServer();

        MockResponse response = new MockResponse();
        response.setBody(mockJwks);
        server.enqueue(response);

        server.start();

        HttpUrl baseUrl = server.url("");

        assertEquals(mockJwks, restClient.getJwks(baseUrl.toString()));

        server.close();
    }
}
