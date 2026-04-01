package com.netcracker.cloud.dbaas.integration.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class WireMockResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(9090);
        wireMockServer.start();

        wireMockServer.stubFor(
                post(urlPathMatching("/api/v1/dbaas/adapter/.*/databases"))
                        .willReturn(aResponse()
                                .withStatus(500)
                                .withHeader("Content-Type", "application/json")
                                .withBody("""
                        {
                          "error": "INTERNAL_ERROR",
                          "message": "Adapter failure"
                        }
                        """))
        );


        return Map.of(
                "wiremock.url", wireMockServer.baseUrl()
        );
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }
}
