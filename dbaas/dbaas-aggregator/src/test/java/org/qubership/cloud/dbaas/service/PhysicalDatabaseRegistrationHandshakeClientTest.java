package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.qubership.cloud.dbaas.dto.HandshakeResponse;
import org.qubership.cloud.dbaas.exceptions.AdapterUnavailableException;
import org.qubership.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import com.sun.net.httpserver.HttpServer;
import io.vertx.mutiny.core.Vertx;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;

class PhysicalDatabaseRegistrationHandshakeClientTest {

    private static HttpServer httpServer;

    private final String POSTGRESQL_TYPE = "postgresql";
    private final String ADAPTER_USERNAME = "username";
    private final String ADAPTER_PWD = "pwd";
    private final String ADAPTER_VERSION_V1 = "v1";
    private final String ADAPTER_VERSION_V2 = "v2";
    private final String TEST_ADAPTER_ADDRESS = "http://localhost:18282";
    private static final String PHYS_DB_ID = "1";


    @BeforeAll
    public static void initServer() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(18282), 0);
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();

        httpServer.createContext("/api/v1/dbaas/adapter/postgresql/physical_database", exchange -> {
            HandshakeResponse resp = new HandshakeResponse();
            resp.setId(PHYS_DB_ID);
            String json = ow.writeValueAsString(resp);
            int responseCode = HttpURLConnection.HTTP_OK;
            exchange.getResponseHeaders().set("Content-type", "application/json");
            exchange.sendResponseHeaders(responseCode, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        });
        httpServer.createContext("/api/v2/dbaas/adapter/postgresql/physical_database", exchange -> {
            HandshakeResponse resp = new HandshakeResponse();
            resp.setId(PHYS_DB_ID);
            String json = ow.writeValueAsString(resp);
            int responseCode = HttpURLConnection.HTTP_NOT_FOUND;
            exchange.getResponseHeaders().set("Content-type", "application/json");
            exchange.sendResponseHeaders(responseCode, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    public static void stopHttpServer() {
        httpServer.stop(0);
    }

    @Test
    void testCorrectHandshake() {
        PhysicalDatabaseRegistrationHandshakeClient client =
                new PhysicalDatabaseRegistrationHandshakeClient(Vertx.vertx());
        Assertions.assertDoesNotThrow(() -> {
            client.handshake(PHYS_DB_ID, TEST_ADAPTER_ADDRESS, POSTGRESQL_TYPE, ADAPTER_USERNAME, ADAPTER_PWD, ADAPTER_VERSION_V1);
        });
    }

    @Test
    void testHandshakeReturnedWrongPhysDbId() {
        PhysicalDatabaseRegistrationHandshakeClient client =
                new PhysicalDatabaseRegistrationHandshakeClient(Vertx.vertx());
        Assertions.assertThrows(PhysicalDatabaseRegistrationConflictException.class, () -> {
            client.handshake("2", TEST_ADAPTER_ADDRESS, POSTGRESQL_TYPE, ADAPTER_USERNAME, ADAPTER_PWD, ADAPTER_VERSION_V1);
        });
    }

    @Test
    void testAdapterUnavailableHandshake() {
        PhysicalDatabaseRegistrationHandshakeClient client =
                new PhysicalDatabaseRegistrationHandshakeClient(Vertx.vertx());
        Assertions.assertThrows(AdapterUnavailableException.class, () -> {
            client.handshake(PHYS_DB_ID, TEST_ADAPTER_ADDRESS, POSTGRESQL_TYPE, ADAPTER_USERNAME, ADAPTER_PWD, ADAPTER_VERSION_V2);
        });
    }
}