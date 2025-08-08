package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.HandshakeResponse;
import org.qubership.cloud.dbaas.exceptions.AdapterUnavailableException;
import org.qubership.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import io.vertx.ext.auth.authentication.UsernamePasswordCredentials;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.codec.BodyCodec;
import io.vertx.mutiny.uritemplate.UriTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class PhysicalDatabaseRegistrationHandshakeClient {

    private static final String ADAPTER_PHYSICAL_DATABASE_PATH = "/api/{version}/dbaas/adapter/{type}/physical_database";

    private final WebClient webClient;

    public PhysicalDatabaseRegistrationHandshakeClient(Vertx vertx) {
        this.webClient = WebClient.create(vertx);
    }

    public void handshake(String physicalDatabaseId, String adapterAddress, String type, String username, String password, String version)
            throws AdapterUnavailableException, PhysicalDatabaseRegistrationConflictException {
        String adapterUrl = adapterAddress + ADAPTER_PHYSICAL_DATABASE_PATH;
        log.info("Sending GET request to {} with 'type' = {} and 'version' = {}", adapterUrl, type, version);
        HttpResponse<HandshakeResponse> httpResponse = webClient.getAbs(UriTemplate.of(adapterUrl))
                .setTemplateParam("version", version)
                .setTemplateParam("type", type)
                .authentication(new UsernamePasswordCredentials(username, password))
                .as(BodyCodec.json(HandshakeResponse.class))
                .sendAndAwait();

        if (Response.Status.OK.getStatusCode() != httpResponse.statusCode()) {
            throw new AdapterUnavailableException(httpResponse.statusCode());
        }
        HandshakeResponse handshakeResponse = httpResponse.body();
        log.info("Response body: {}", handshakeResponse);
        String phyDBId = handshakeResponse.getId();
        if (!phyDBId.equals(physicalDatabaseId)) {
            throw new PhysicalDatabaseRegistrationConflictException(String.format("Adapter responded with wrong physical database identifier. " +
                    "Expected %s, got: %s", physicalDatabaseId, phyDBId));
        }
    }
}
