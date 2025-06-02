package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.connections.handlers.CassandraConnectionHandler;
import org.qubership.cloud.dbaas.connections.handlers.ConnectionHandlerFactory;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.model.DatabaseMonitoringEntryStatus;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfo;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus.HEALTH_CHECK_STATUS_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    @InjectMocks
    private MonitoringService monitoringService;

    @Mock
    PhysicalDatabasesService physicalDatabasesService;

    @Mock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Spy
    ConnectionHandlerFactory connectionHandlerFactory
            = new ConnectionHandlerFactory(Collections.singletonList(new CassandraConnectionHandler()));

    @Test
    void getDatabaseMonitoringEntryStatus() {
        DbaasAdapter adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        AdapterHealthStatus upStatus = new AdapterHealthStatus(HEALTH_CHECK_STATUS_UP);
        when(adapter.getAdapterHealth()).thenReturn(upStatus);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("id");
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter));

        Database database1 = new Database();
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("host", "someHost");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        database1.setConnectionProperties(Arrays.asList(connectionProperties));

        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "someMS1");
        database1.setAdapterId("id");
        database1.setClassifier(classifier);
        database1.setType("someType");
        database1.setNamespace("namespace");
        DatabaseRegistry registry1 = new DatabaseRegistry();
        registry1.setClassifier(classifier);
        registry1.setType("someType");
        registry1.setNamespace("namespace");
        database1.setDatabaseRegistry(List.of(registry1));
        registry1.setDatabase(database1);

        Database database2 = new Database();
        Map<String, Object> emptyConnProperties = new HashMap<>();
        database2.setConnectionProperties(Arrays.asList(emptyConnProperties));

        SortedMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put("microserviceName", "someMS2");
        database2.setClassifier(classifier2);
        database2.setAdapterId("id");
        database2.setType("someType");
        database2.setNamespace("namespace");
        DatabaseRegistry registry2 = new DatabaseRegistry();
        registry2.setClassifier(classifier);
        registry2.setType("someType");
        registry2.setNamespace("namespace");
        database2.setDatabaseRegistry(List.of(registry2));
        registry2.setDatabase(database2);

        when(databaseRegistryDbaasRepository.findAllDatabasesAnyLogTypeFromCache()).thenReturn(Arrays.asList(registry1, registry2));

        List<DatabaseMonitoringEntryStatus> databaseMonitoringEntryStatus = monitoringService.getDatabaseMonitoringEntryStatus();
        assertEquals(databaseMonitoringEntryStatus.get(0).getStatus(), HEALTH_CHECK_STATUS_UP);
        assertEquals("someHost", databaseMonitoringEntryStatus.get(0).getHost());
        assertEquals("someMS1", databaseMonitoringEntryStatus.get(0).getMicroservice());
        assertEquals("namespace", databaseMonitoringEntryStatus.get(0).getNamespace());
        assertEquals("someType", databaseMonitoringEntryStatus.get(0).getDatabaseType());
        assertEquals("null", databaseMonitoringEntryStatus.get(0).getDatabaseName());
        assertEquals("false", databaseMonitoringEntryStatus.get(0).getExternallyManageable());
    }

    @Test
    void getDatabasesStatus() {
        DbaasAdapter adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("id");
        when(adapter.getDatabases()).thenReturn(Set.of("db-name-1")); // !
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter));

        Database database1 = new Database();
        database1.setAdapterId("id1");
        database1.setName("db-name-1");
        database1.setType("someType");
        database1.setNamespace("namespace");
        DatabaseRegistry registry1 = new DatabaseRegistry();
        registry1.setType("someType");
        registry1.setNamespace("namespace");
        registry1.setDatabase(database1);

        Database database2 = new Database();
        database2.setAdapterId("id2");
        database2.setName(null); // !
        database2.setType("someType");
        database2.setNamespace("namespace");
        DatabaseRegistry registry2 = new DatabaseRegistry();
        registry2.setType("someType");
        registry2.setNamespace("namespace");
        registry2.setDatabase(database2);

        Database database3 = new Database();
        database3.setAdapterId("id3");
        database3.setName("db-name-3"); // !
        database3.setType("someType");
        database3.setNamespace("namespace");
        DatabaseRegistry registry3 = new DatabaseRegistry();
        registry3.setType("someType");
        registry3.setNamespace("namespace");
        registry3.setDatabase(database3);

        when(databaseRegistryDbaasRepository.findAllInternalDatabases()).thenReturn(Arrays.asList(registry1, registry2, registry3));

        DatabasesInfo databasesStatus = monitoringService.getDatabasesStatus();

        assertEquals(1, databasesStatus.getGlobal().getTotalDatabases().size());
        assertEquals(0, databasesStatus.getGlobal().getDeletingDatabases().size());
        assertEquals(0, databasesStatus.getGlobal().getRegistration().getGhostDatabases().size());
        assertEquals(2, databasesStatus.getGlobal().getRegistration().getLostDatabases().size());
        assertEquals(3, databasesStatus.getGlobal().getRegistration().getTotalDatabases().size());
    }

    @Test
    void unknownStatusWhenAdapterNotExist() {
        Database database = new Database();
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "someMS");
        database.setClassifier(classifier);
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("host", "someHost");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        database.setConnectionProperties(Collections.singletonList(connectionProperties));
        DatabaseRegistry registry = new DatabaseRegistry();
        registry.setClassifier(classifier);
        registry.setDatabase(database);
        when(databaseRegistryDbaasRepository.findAllDatabasesAnyLogTypeFromCache()).thenReturn(Collections.singletonList(registry));
        List<DatabaseMonitoringEntryStatus> databaseMonitoringEntryStatus = monitoringService.getDatabaseMonitoringEntryStatus();
        Assertions.assertEquals(1, databaseMonitoringEntryStatus.size());
        Assertions.assertEquals("null", databaseMonitoringEntryStatus.get(0).getStatus());
    }


    @Test
    void getDatabaseMonitoringEntryStatusEmptyMicroserviceName() {
        DbaasAdapter adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        AdapterHealthStatus upStatus = new AdapterHealthStatus(HEALTH_CHECK_STATUS_UP);
        when(adapter.getAdapterHealth()).thenReturn(upStatus);
        when(adapter.isDisabled()).thenReturn(false);
        when(adapter.identifier()).thenReturn("id");
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Arrays.asList(adapter));
        DatabaseRegistry database = mock(DatabaseRegistry.class);
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("host", "someHost");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        when(database.getConnectionProperties()).thenReturn(Arrays.asList(connectionProperties));
        SortedMap<String, Object> classifier = new TreeMap<>();
        when(database.getClassifier()).thenReturn(classifier);
        when(database.getAdapterId()).thenReturn("id");
        when(database.getType()).thenReturn("someType");
        when(database.getNamespace()).thenReturn("namespace");
        when(databaseRegistryDbaasRepository.findAllDatabasesAnyLogTypeFromCache()).thenReturn(Arrays.asList(database));

        List<DatabaseMonitoringEntryStatus> databaseMonitoringEntryStatus = monitoringService.getDatabaseMonitoringEntryStatus();
        assertEquals(databaseMonitoringEntryStatus.get(0).getStatus(), HEALTH_CHECK_STATUS_UP);
        assertEquals("someHost", databaseMonitoringEntryStatus.get(0).getHost());
        assertEquals("null", databaseMonitoringEntryStatus.get(0).getMicroservice());
        assertEquals("namespace", databaseMonitoringEntryStatus.get(0).getNamespace());
        assertEquals("someType", databaseMonitoringEntryStatus.get(0).getDatabaseType());
    }
}