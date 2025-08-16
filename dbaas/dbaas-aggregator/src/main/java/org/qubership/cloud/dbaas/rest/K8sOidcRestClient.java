package org.qubership.cloud.dbaas.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Shutdown;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.tls.Certificates;
import okhttp3.tls.HandshakeCertificates;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.dbaas.dto.oidc.OidcConfig;
import org.qubership.cloud.dbaas.security.interceptors.K8sTokenInterceptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

@ApplicationScoped
@Slf4j
public class K8sOidcRestClient {
    private final OkHttpClient client;
    private K8sTokenInterceptor k8sTokenInterceptor;

    public K8sOidcRestClient(@ConfigProperty(name = "dbaas.security.k8s.jwks.secure") boolean isKubernetesIdpSecure,
                             @ConfigProperty(name = "dbaas.security.k8s.jwt.token.service-account.cert-path") String caCertPath,
                             @ConfigProperty(name = "dbaas.security.k8s.jwt.token.service-account.path") String tokenLocation,
                             @ConfigProperty(name = "dbaas.security.k8s.jwt.token.service-account.dir") String tokenDir) throws IOException {
        Builder builder = new OkHttpClient.Builder();

        if (isKubernetesIdpSecure) {
            setSslSocketFactory(builder, caCertPath);
            k8sTokenInterceptor = new K8sTokenInterceptor(tokenLocation, tokenDir);
            builder.addInterceptor(k8sTokenInterceptor);
        }

        client = builder.build();
    }

    public OidcConfig getOidcConfiguration(String oidcProviderUrl) throws RuntimeException {
        Request request = new Request.Builder()
                .url(oidcProviderUrl + "/.well-known/openid-configuration")
                .build();

        Call call = client.newCall(request);
        try (Response response = call.execute()) {
            ObjectMapper objectMapper = new ObjectMapper();
            if (response.body() == null) {
                throw new RuntimeException("Response for requesting oidc configuration from Kubernetes IDP does not have response body");
            }
            return objectMapper.readValue(response.body().string(), OidcConfig.class);
        } catch (IOException e) {
            log.error("Failed to retrieve OIDC configuration from Kubernetes IDP", e);
            throw new RuntimeException(e);
        }
    }

    public String getJwks(String jwksEndpoint) throws IOException {
        Request request = new Request.Builder()
                .url(jwksEndpoint)
                .build();

        Call call = client.newCall(request);
        try (Response response = call.execute()) {
            if (response.body() == null) {
                throw new RuntimeException("Response for requesting jwks from Kubernetes IDP does not have response body");
            }
            return response.body().string();
        }
    }

    private void setSslSocketFactory(Builder builder, String caCertPath) throws IOException {
        X509Certificate caCert = Certificates.decodeCertificatePem(Files.readString(Path.of(caCertPath)));

        HandshakeCertificates certificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(caCert)
                .build();

        builder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager());
    }


    @Shutdown
    void shutdown() {
        if (k8sTokenInterceptor != null) {
            k8sTokenInterceptor.close();
        }
    }
}
