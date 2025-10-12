package com.netcracker.cloud.dbaas.integration.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.netcracker.cloud.dbaas.integration.utils.TestJwtUtils;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesServiceAccountToken;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@Slf4j
public class MockOidcTestResource implements QuarkusTestResourceLifecycleManager {
    private static final String ENCODED_X5C = "MIIC+zCCAeOgAwIBAgIGAXx/E9rgMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDAeFw0yMTEwMTQxMzUzMDBaFw0yMjEwMTQxMzUzMDBaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAIicN95dXlQLBqEZUsqPhQopnjnPgGmW80NohEgNzZLqN0xW9cyJJrdJM5Z1lRrePHZGiJdd1XXn4fYasP6/cjRfMWal9X6dD5wlnOTP01/4beX5vctE6W4lZrI3kTFmZ+I69w7BaLsUPWgV1CYrtuldL3dr6xAnngK3hU+JraB2Ndw9llXib26HOZhCXKedCTYcUQieVJGPI0f8H1JNk88+PnwI+cUGgXHF56iTLv9QujI6AhIgextXdd21T0XiHgBkSlSSBeqIKAjfCW6zoXP+PJU+Lso24J3duG3mrbilqHZlmIWnLRaG0RmKOeedXIDHvAaMaVUOLaN9HBgNKo0CAwEAAaNTMFEwHQYDVR0OBBYEFMYGoBNHBTMvMT4DwClVHVVwn+5VMB8GA1UdIwQYMBaAFMYGoBNHBTMvMT4DwClVHVVwn+5VMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAFulB0DKhykXGbGPIBPcj63ItLNilgl1i8i43my8fYdV6OBWLIhZ4InhpX1+XmYCNPNtu94Jy1csS00K2/Hhn4ByBd+6nd5DSr0W0VdVQyhLz3GW1nf0J3X2N+tD818O0KtKKPTq4p9reg/XtV+DNv7DeDAGzlfgRL4E4fQx6OYeuu35kGrPvAddIA70leJMELJRylCLfEcl2ne/Bht8cZVp7ZCxnfXnsc+7hCW84mhzGjJycA3E6TnZPD3pD+q9FoIAQMxMQqUCH71u9vTvz1Q5JdokuJJY2eTHSUKyHA9MwSFq8DFDICJFBoQuFyDlK5yxSUcQpR3mBwKdimj6oA0=";

    private WireMockServer server;

    @SneakyThrows
    @Override
    public Map<String, String> start() {
        server = new WireMockServer(TestJwtUtils.getOidcPort());
        server.start();
        log.info("WireMock OIDC server started on port {}", server.port());

        server.stubFor(
                get(urlEqualTo("/auth/realms/quarkus/.well-known/openid-configuration"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\n" +
                                        "  \"jwks_uri\": \"" + server.baseUrl() +
                                        "/auth/realms/quarkus/protocol/openid-connect/certs\"\n" +
                                        "}")
                        ));

        server.stubFor(
                get(urlEqualTo("/auth/realms/quarkus/protocol/openid-connect/certs"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\n" +
                                        "  \"keys\" : [\n" +
                                        "    {\n" +
                                        "      \"kid\": \"1\",\n" +
                                        "      \"kty\":\"RSA\",\n" +
                                        "      \"n\":\"iJw33l1eVAsGoRlSyo-FCimeOc-AaZbzQ2iESA3Nkuo3TFb1zIkmt0kzlnWVGt48dkaIl13Vdefh9hqw_r9yNF8xZqX1fp0PnCWc5M_TX_ht5fm9y0TpbiVmsjeRMWZn4jr3DsFouxQ9aBXUJiu26V0vd2vrECeeAreFT4mtoHY13D2WVeJvboc5mEJcp50JNhxRCJ5UkY8jR_wfUk2Tzz4-fAj5xQaBccXnqJMu_1C6MjoCEiB7G1d13bVPReIeAGRKVJIF6ogoCN8JbrOhc_48lT4uyjbgnd24beatuKWodmWYhactFobRGYo5551cgMe8BoxpVQ4to30cGA0qjQ\",\n"
                                        +
                                        "      \"e\":\"AQAB\"\n" +
                                        "    },\n" +
                                        "    {" +
                                        "      \"kty\": \"RSA\"," +
                                        "      \"alg\": \"RS256\"," +
                                        "      \"n\":\"iJw33l1eVAsGoRlSyo-FCimeOc-AaZbzQ2iESA3Nkuo3TFb1zIkmt0kzlnWVGt48dkaIl13Vdefh9hqw_r9yNF8xZqX1fp0PnCWc5M_TX_ht5fm9y0TpbiVmsjeRMWZn4jr3DsFouxQ9aBXUJiu26V0vd2vrECeeAreFT4mtoHY13D2WVeJvboc5mEJcp50JNhxRCJ5UkY8jR_wfUk2Tzz4-fAj5xQaBccXnqJMu_1C6MjoCEiB7G1d13bVPReIeAGRKVJIF6ogoCN8JbrOhc_48lT4uyjbgnd24beatuKWodmWYhactFobRGYo5551cgMe8BoxpVQ4to30cGA0qjQ\",\n"
                                        +
                                        "      \"e\":\"AQAB\",\n" +
                                        "      \"x5c\": [" +
                                        "          \"" + ENCODED_X5C + "\""
                                        +
                                        "      ]" +
                                        "    }" +
                                        "  ]\n" +
                                        "}")));

        System.setProperty(KubernetesServiceAccountToken.SERVICE_ACCOUNT_DIR_PROP, "./src/test/resources/mock-oidc-token");

        return Map.of(
                "smallrye.jwt.sign.key.location", "privateKey.jwk"
        );
    }

    @Override
    public void stop() {
        if (null != server) {
            server.stop();
        }
    }
}
