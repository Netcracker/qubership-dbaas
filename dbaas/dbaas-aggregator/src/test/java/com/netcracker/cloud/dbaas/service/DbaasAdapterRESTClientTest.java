package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import com.netcracker.cloud.dbaas.dto.AdapterDatabaseCreateRequest;
import com.netcracker.cloud.dbaas.dto.CreatedDatabase;
import com.netcracker.cloud.dbaas.dto.DatabaseCreateRequest;
import com.netcracker.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClient;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class DbaasAdapterRESTClientTest {

    static DbaasAdapterRESTClient dbaasAdapterRESTClient;

    static DbaasAdapterRestClient restClient;

    static AdapterActionTrackerClient tracker;

    @BeforeAll
    static void setUp() {
        restClient = Mockito.mock(DbaasAdapterRestClient.class);
        tracker = Mockito.mock(AdapterActionTrackerClient.class);
        dbaasAdapterRESTClient = new DbaasAdapterRESTClient("adapterAddress", "type", restClient,
                "identifier", tracker);
    }

    @Test
    void createDatabase() {
        AbstractDatabaseCreateRequest databaseCreateRequest = new DatabaseCreateRequest();
        String microserviceName = "test_microserviceName";
        CreatedDatabase responseEntity = new CreatedDatabase();
        Mockito.doReturn(responseEntity).when(restClient).createDatabase(anyString(), any(AdapterDatabaseCreateRequest.class));
        dbaasAdapterRESTClient.createDatabase(databaseCreateRequest, microserviceName);
        Mockito.verify(restClient).createDatabase(anyString(), any(AdapterDatabaseCreateRequest.class));
    }

    @Test
    void createDatabaseV3() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dbaasAdapterRESTClient.createDatabaseV3(new DatabaseCreateRequestV3(), "ms1"));
    }

    @Test
    void ensureUser() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dbaasAdapterRESTClient.ensureUser("username", "pswd", "dbname", "admin"));
    }

    @Test
    void restorePasswords() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dbaasAdapterRESTClient.restorePasswords(new HashMap<>(), Collections.emptyList()));
    }

    @Test
    void createUser() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dbaasAdapterRESTClient.createUser("user", "password", "dbName", "admin"));
    }

    @Test
    void deleteUser() {
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> dbaasAdapterRESTClient.deleteUser(Collections.emptyList()));
    }
}
