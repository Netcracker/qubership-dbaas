package com.netcracker.cloud.dbaas.mock;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import com.netcracker.cloud.dbaas.TestJwtUtils;
import com.netcracker.cloud.dbaas.dto.oidc.OidcConfig;
import com.netcracker.cloud.dbaas.rest.K8sOidcRestClient;

import java.io.IOException;

@ApplicationScoped
@Alternative
@Priority(1)
public class MockedK8sOidcRestClient extends K8sOidcRestClient {
    @Inject
    TestJwtUtils jwtUtils;

    public MockedK8sOidcRestClient() throws IOException {
        super(false, "");
    }

    @Override
    public OidcConfig getOidcConfiguration() throws RuntimeException {
        return new OidcConfig("");
    }

    @Override
    public String getJwks(String jwksEndpoint) {
        return jwtUtils.getJwks();
    }
}
