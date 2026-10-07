package com.netcracker.cloud.dbaas.integration.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

public class WireMockResource implements QuarkusTestResourceLifecycleManager {
    private static WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
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

    public static WireMockServer getServer() {
        return wireMockServer;
    }
}
