package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import jakarta.ws.rs.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AdapterSupportsTest {

    @Mock
    private AdapterActionTrackerClient tracker;
    @Mock
    private DbaasAdapterRestClientV2 restClient;

    private AdapterSupports adapterSupports;
    private DbaasAdapterRESTClientV2 dbaasAdapterRESTClient;

    @BeforeEach
    public void init() {
        dbaasAdapterRESTClient = new DbaasAdapterRESTClientV2("", "", restClient, "", tracker);
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2");
    }

    @Test
    public void testSettings() {

        final String settings = "settings";
        final Map<String, Boolean> responseBody = new HashMap<>();
        responseBody.put(settings, true);

        when(restClient.supports(anyString())).thenReturn(responseBody);
        boolean actualValue = adapterSupports.settings();
        assertTrue(actualValue);

        when(restClient.supports(anyString())).thenReturn(new HashMap<>());
        actualValue = adapterSupports.settings();
        assertFalse(actualValue);
    }

    @Test
    public void testUsers() {
        final String users = "users";
        final Map<String, Boolean> responseBody = new HashMap<>();
        responseBody.put(users, false);

        when(restClient.supports(anyString())).thenReturn(responseBody);

        boolean actualValue = adapterSupports.users();
        assertFalse(actualValue);

        when(restClient.supports(anyString())).thenReturn(new HashMap<>());
        actualValue = adapterSupports.users();
        assertTrue(actualValue);
    }

    @Test
    public void testDescribeDatabases() {
        final String describeDatabases = "describeDatabases";
        final Map<String, Boolean> responseBody = new HashMap<>();
        responseBody.put(describeDatabases, true);

        when(restClient.supports(anyString())).thenReturn(responseBody);

        boolean actualValue = adapterSupports.describeDatabases();
        assertTrue(actualValue);

        when(restClient.supports(anyString())).thenReturn(new HashMap<>());
        actualValue = adapterSupports.describeDatabases();
        assertFalse(actualValue);
    }

    @Test
    public void testSettingsWith404() {
        when(restClient.supports(anyString())).thenThrow(new NotFoundException());

        boolean actualValue = adapterSupports.settings();
        assertFalse(actualValue);
    }

    @Test
    public void testContract_exact() {
        ApiVersion apiVersion = new ApiVersion(List.of(new ApiVersion.Spec("/api", 1, 2, List.of(1))));
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2", apiVersion);
        assertTrue(adapterSupports.contract(1, 2));
    }

    @Test
    public void testContract_minorBelow() {

        ApiVersion apiVersion = new ApiVersion(List.of(new ApiVersion.Spec("/api", 1, 3, List.of(1))));
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2", apiVersion);
        assertTrue(adapterSupports.contract(1, 1));
    }

    @Test
    public void testContract_minorAbove() {
        ApiVersion apiVersion = new ApiVersion(List.of(new ApiVersion.Spec("/api", 1, 1, List.of(1))));
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2", apiVersion);
        assertFalse(adapterSupports.contract(1, 3));
    }

    @Test
    public void testContract_lowerMajorNotSupported() {
        ApiVersion apiVersion = new ApiVersion(List.of(new ApiVersion.Spec("/api", 3, 0, List.of(3))));
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2", apiVersion);
        assertFalse(adapterSupports.contract(2, 1));
    }

    @Test
    public void testContract_lowerMajorSupported() {
        ApiVersion apiVersion = new ApiVersion(List.of(new ApiVersion.Spec("/api", 3, 0, List.of(3, 2))));
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2", apiVersion);
        assertTrue(adapterSupports.contract(2, 1));
    }

    @Test
    public void testContract_majorAbove() {
        ApiVersion apiVersion = new ApiVersion(List.of(new ApiVersion.Spec("/api", 1, 2, List.of(1))));
        adapterSupports = new AdapterSupports(dbaasAdapterRESTClient, "v2", apiVersion);
        assertFalse(adapterSupports.contract(2, 1));
    }
}
