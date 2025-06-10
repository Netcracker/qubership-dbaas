package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.dto.LinkDatabasesRequest;
import org.qubership.cloud.dbaas.dto.v3.UpdateHostRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.exceptions.NotFoundException;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.MICROSERVICE_NAME;
import static org.qubership.cloud.dbaas.service.DBaaService.MARKED_FOR_DROP;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationServiceTest {

    private DBaaService dBaaService;
    private PhysicalDatabasesService physicalDatabasesService;
    private LogicalDbDbaasRepository logicalDbDbaasRepository;
    private OperationService operationService;

    @BeforeEach
    public void setup() {
        dBaaService = mock(DBaaService.class);
        physicalDatabasesService = mock(PhysicalDatabasesService.class);
        logicalDbDbaasRepository = mock(LogicalDbDbaasRepository.class);
        operationService = new OperationService(dBaaService, physicalDatabasesService, logicalDbDbaasRepository);
    }

    @Test
    public void testChangeHost_ValidRequests_onCopy() {
        testOnCopy(true);
        verify(dBaaService, times(2)).saveDatabaseRegistry(any(DatabaseRegistry.class));
        verify(dBaaService, times(1)).markDatabasesAsOrphan(any(DatabaseRegistry.class));
    }

    @Test
    public void testChangeHost_ValidRequests_onCopy_withOldOrphan() {
        UpdateHostRequest request = getUpdateHostRequest();
        request.setMakeCopy(true);
        request.setPhysicalDatabaseId("physicalDatabaseId2");

        // DB for host change
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(new Database());
        databaseRegistry.setName("dbName");
        databaseRegistry.setType(DatabaseType.POSTGRESQL.name());
        databaseRegistry.setPhysicalDatabaseId("physicalDatabaseId1");
        databaseRegistry.setConnectionProperties(List.of(new HashMap<>(Map.of(
                "host", "originHost",
                "url", "jdbc:postgresql://originHost:5432/dbaas_d11b5fd935e548a6bf8574d35db45555"
        ))));
        databaseRegistry.setResources(List.of(new DbResource("user", "user1"), new DbResource("user", "user2")));
        when(dBaaService.findDatabaseByClassifierAndType(eq(request.getClassifier()), anyString(), anyBoolean()))
                .thenReturn(databaseRegistry);

        // Old orphan DB in the target physical DB
        TreeMap<String, Object> orphanClassifier = new TreeMap<>(request.getClassifier());
        orphanClassifier.put(MARKED_FOR_DROP, MARKED_FOR_DROP);
        DatabaseRegistry databaseRegistryOrphan = new DatabaseRegistry();
        databaseRegistryOrphan.setDatabase(new Database());
        databaseRegistryOrphan.setName("dbName");
        databaseRegistryOrphan.setClassifier(orphanClassifier);
        databaseRegistryOrphan.setType(DatabaseType.POSTGRESQL.name());
        databaseRegistryOrphan.setPhysicalDatabaseId("physicalDatabaseId2");
        databaseRegistryOrphan.setConnectionProperties(List.of(new HashMap<>(Map.of(
                "host", "newHost",
                "url", "jdbc:postgresql://newHost:5432/dbaas_d11b5fd935e548a6bf8574d35db45555"
        ))));
        databaseRegistryOrphan.setResources(List.of(new DbResource("user", "user2"), new DbResource("user", "user3")));
        databaseRegistryOrphan.setDatabaseRegistry(List.of(databaseRegistryOrphan));
        databaseRegistryOrphan.setMarkedForDrop(true);
        when(dBaaService.findDatabaseByClassifierAndType(eq(orphanClassifier), anyString(), anyBoolean()))
                .thenReturn(databaseRegistryOrphan);

        DbaasAdapter adapter = mock(DbaasAdapter.class);
        String newAdapterId = "newAdapterId";
        when(adapter.identifier()).thenReturn(newAdapterId);
        when(physicalDatabasesService.getAdapterByPhysDbId(eq("physicalDatabaseId2")))
                .thenReturn(adapter);

        DatabaseRegistryDbaasRepository drdr = mock(DatabaseRegistryDbaasRepository.class);
        DatabaseDbaasRepository ddr = mock(DatabaseDbaasRepository.class);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(drdr);
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(ddr);
        when(ddr.findByNameAndAdapterId(eq(databaseRegistry.getName()), eq(newAdapterId)))
                .thenReturn(Optional.of(databaseRegistryOrphan.getDatabase()));

        when(dBaaService.makeCopy(any())).thenReturn(databaseRegistry);

        // Execute method
        List<DatabaseRegistry> results = operationService.changeHost(List.of(request));

        // Verify changes and interactions
        assertNotNull(results);
        assertEquals(1, results.size());
        DatabaseRegistry result = results.getFirst();
        assertEquals("physicalDatabaseId2", result.getPhysicalDatabaseId());
        assertEquals("newHost", result.getConnectionProperties().getFirst().get("host"));
        assertEquals("jdbc:postgresql://newHost:5432/dbaas_d11b5fd935e548a6bf8574d35db45555", result.getConnectionProperties().getFirst().get("url"));

        verify(adapter, times(1)).deleteUser(argThat(
                dbResources -> dbResources.size() == 1 && dbResources.getFirst().getName().equals("user3")));
        verify(adapter, times(0)).dropDatabase(any(DatabaseRegistry.class));
        verify(adapter, times(0)).dropDatabase(any(Database.class));
        verify(drdr, times(1)).deleteById(eq(databaseRegistryOrphan.getId()));
        verify(dBaaService, times(1)).makeCopy(any(DatabaseRegistry.class));
        verify(dBaaService, times(1)).markDatabasesAsOrphan(eq(databaseRegistry));
        verify(dBaaService, times(2)).saveDatabaseRegistry(any(DatabaseRegistry.class));
    }

    @Test
    public void testChangeHost_ValidRequests_inPlace() {
        testOnCopy(false);
        verify(dBaaService, times(1)).saveDatabaseRegistry(any(DatabaseRegistry.class));
    }

    private void testOnCopy(Boolean copy) {
        // Mock behavior for dBaaService and physicalDatabasesService
        UpdateHostRequest request = getUpdateHostRequest();
        if (copy) {
            request.setMakeCopy(true);
        }
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(new Database());
        databaseRegistry.setType(DatabaseType.POSTGRESQL.name());
        databaseRegistry.setConnectionProperties(List.of(new HashMap<>(Map.of(
                "host", "originHost",
                "url", "jdbc:postgresql://originHost:5432/dbaas_d11b5fd935e548a6bf8574d35db45555"
        ))));
        when(dBaaService.findDatabaseByClassifierAndType(eq(request.getClassifier()), anyString(), anyBoolean()))
                .thenReturn(databaseRegistry);
        DbaasAdapter adapter = mock(DbaasAdapter.class);
        when(adapter.identifier()).thenReturn("newAdapterId");
        when(physicalDatabasesService.getAdapterByPhysDbId(eq("physicalDatabaseId")))
                .thenReturn(adapter);
        if (copy) {
            when(dBaaService.makeCopy(any())).thenReturn(databaseRegistry);
        }
        DatabaseDbaasRepository ddr = mock(DatabaseDbaasRepository.class);
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(ddr);

        // Execute method
        List<DatabaseRegistry> results = operationService.changeHost(List.of(request));

        // Verify changes and interactions
        assertNotNull(results);
        assertEquals(1, results.size());
        DatabaseRegistry result = results.getFirst();
        assertEquals("physicalDatabaseId", result.getPhysicalDatabaseId());
        assertEquals("newHost", result.getConnectionProperties().getFirst().get("host"));
        assertEquals("jdbc:postgresql://newHost:5432/dbaas_d11b5fd935e548a6bf8574d35db45555", result.getConnectionProperties().getFirst().get("url"));
        verify(adapter, times(0)).dropDatabase(any(DatabaseRegistry.class));
        verify(adapter, times(0)).dropDatabase(any(Database.class));
    }

    @Test
    public void testChangeHost_DatabaseNotFound() {
        when(dBaaService.findDatabaseByClassifierAndType(anyMap(), anyString(), anyBoolean()))
                .thenReturn(null);

        UpdateHostRequest request = getUpdateHostRequest();

        assertThrows(NotFoundException.class, () -> operationService.changeHost(request));
    }

    @Test
    public void testChangeHost_OriginHostNotFound() {
        // Mock behavior for dBaaService
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(new Database());
        databaseRegistry.setConnectionProperties(List.of(Map.of()));
        when(dBaaService.findDatabaseByClassifierAndType(anyMap(), anyString(), anyBoolean()))
                .thenReturn(databaseRegistry);

        UpdateHostRequest request = getUpdateHostRequest();

        assertThrows(RuntimeException.class, () -> operationService.changeHost(request));
    }

    private static @NotNull UpdateHostRequest getUpdateHostRequest() {
        UpdateHostRequest request = new UpdateHostRequest();
        Map<String, Object> classifier = Map.of("microserviceName", "test-ms",
                "namespace", "test-ns", "scope", "service");
        request.setClassifier(classifier);
        request.setType(DatabaseType.POSTGRESQL.name());
        request.setMakeCopy(false);
        request.setPhysicalDatabaseHost("newHost");
        request.setPhysicalDatabaseId("physicalDatabaseId");
        return request;
    }

    @Test
    public void testLinkDbsToNamespace_ValidRequest() {
        String sourceNamespace = "source";
        String targetNamespace = "target";
        String msName = "requiredMs";

        DatabaseRegistry databaseRegistry1 = new DatabaseRegistry();
        Database database1 = new Database();
        database1.setId(UUID.randomUUID());
        databaseRegistry1.setDatabase(database1);
        TreeMap<String, Object> classifier1 = new TreeMap<>();
        classifier1.put(MICROSERVICE_NAME, msName);
        databaseRegistry1.setClassifier(classifier1);

        DatabaseRegistry databaseRegistry2 = new DatabaseRegistry();
        Database database2 = new Database();
        database2.setId(UUID.randomUUID());
        databaseRegistry2.setDatabase(database2);
        TreeMap<String, Object> classifier2 = new TreeMap<>();
        classifier2.put(MICROSERVICE_NAME, "anotherMs");
        databaseRegistry2.setClassifier(classifier2);
        databaseRegistry2.setConnectionProperties(List.of(Map.of()));

        DatabaseRegistryDbaasRepository drdr = mock(DatabaseRegistryDbaasRepository.class);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(drdr);
        when(drdr.findAnyLogDbRegistryTypeByNamespace(eq(sourceNamespace))).thenReturn(List.of(databaseRegistry1, databaseRegistry2));
        when(dBaaService.shareDbToNamespace(any(), eq(targetNamespace)))
                .thenAnswer(invocation -> new DatabaseRegistry(invocation.getArgument(0), targetNamespace));

        LinkDatabasesRequest request = new LinkDatabasesRequest(List.of(msName), targetNamespace);
        List<DatabaseRegistry> newRegistries = operationService.linkDbsToNamespace(sourceNamespace, request);
        assertEquals(1, newRegistries.size());
        assertEquals(targetNamespace, newRegistries.get(0).getNamespace());
        assertEquals(databaseRegistry1.getDatabase().getId(), newRegistries.get(0).getDatabase().getId());

        verify(dBaaService, times(1)).shareDbToNamespace(databaseRegistry1, targetNamespace);
        verify(dBaaService, times(0)).shareDbToNamespace(databaseRegistry2, targetNamespace);
    }
}