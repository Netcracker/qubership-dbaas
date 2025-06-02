package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.dto.bluegreen.BgStateRequest;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.entity.pg.backup.RestoreResult;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgDomainRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgTrackRepository;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import jakarta.ws.rs.core.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.service.DBaaService.MARKED_FOR_DROP;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlueGreenServiceTest {

    public static final String NS_1 = "ns1";
    public static final String NS_2 = "ns2";
    public static final String NS_C = "controller";

    @Mock
    DBBackupsService backupsService;
    @Mock
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Mock
    BalancingRulesService balancingRulesService;
    @Mock
    DatabaseRolesService databaseRolesService;
    @Mock
    DeclarativeDbaasCreationService declarativeDbaasCreationService;
    @Mock
    DBaaService dBaaService;
    @Mock
    ProcessService processService;
    @Mock
    BgTrackRepository bgTrackRepository;
    @Mock
    DatabaseConfigurationCreationService databaseConfigurationCreationService;
    @Mock
    BgDomainRepository bgDomainRepository;
    @Mock
    AggregatedDatabaseAdministrationService aggregatedDatabaseAdministrationService;
    @Mock
    BgNamespaceRepository bgNamespaceRepository;
    @Mock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Mock
    BackupsDbaasRepository backupsDbaasRepository;
    @Mock
    DatabaseDbaasRepository databaseDbaasRepository;

    BlueGreenService blueGreenService;

    @BeforeEach
    public void setup() {
        blueGreenService = new BlueGreenService(backupsService, logicalDbDbaasRepository,
                aggregatedDatabaseAdministrationService, bgNamespaceRepository, balancingRulesService,
                databaseRolesService, bgDomainRepository, dBaaService, bgTrackRepository, processService, declarativeDbaasCreationService,
                backupsDbaasRepository, databaseConfigurationCreationService);
    }

    @Test
    void warmupLazy() {
        when(balancingRulesService.copyNamespaceRule(any(), any())).thenReturn(null);
        when(balancingRulesService.copyMicroserviceRule(any(), any())).thenReturn(null);
        when(databaseRolesService.copyDatabaseRole(any(), any())).thenReturn(null);
        when(declarativeDbaasCreationService.findAllByNamespace("test-namespace-active")).thenReturn(Collections.emptyList());

        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace("test-namespace-active");
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setVersion("v2");
        bgNamespaceActive.setBgDomain(bgDomain);


        BgNamespace bgNamespaceIdle = new BgNamespace();
        bgNamespaceIdle.setNamespace("test-namespace-candidate");
        bgNamespaceIdle.setState(IDLE_STATE);
        bgNamespaceIdle.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceIdle));

        BgStateRequest.BGStateNamespace bgRequestedNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, "test-namespace-candidate", "v2");
        BgStateRequest.BGStateNamespace bgRequestedNamespaceActive = createBgStateNamespace(ACTIVE_STATE, "test-namespace-active", "v1");

        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgRequestedNamespaceActive);
        bgState.setPeerNamespace(bgRequestedNamespaceCandidate);

        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-candidate")).thenReturn(Optional.of(bgNamespaceIdle));
        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-active")).thenReturn(Optional.of(bgNamespaceActive));
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        SortedMap<String, Object> classifier = createClassifier("test", "test-namespace-active");
        DatabaseRegistry databaseRegistry = createDatabaseRegistry(classifier, "postgresql", "123", "username", "dbName");
        databaseRegistry.setNamespace("test-namespace-active");
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace-active")).thenReturn(List.of(databaseRegistry));

        blueGreenService.warmup(bgState);
        verify(balancingRulesService, times(1)).copyNamespaceRule("test-namespace-active", "test-namespace-candidate");
        verify(balancingRulesService, times(1)).copyMicroserviceRule("test-namespace-active", "test-namespace-candidate");
        verify(databaseRolesService, times(1)).copyDatabaseRole("test-namespace-active", "test-namespace-candidate");
        verify(declarativeDbaasCreationService, times(1)).findAllByNamespace("test-namespace-active");
        verify(databaseRegistryDbaasRepository).findAnyLogDbRegistryTypeByNamespace("test-namespace-active");
        verify(dBaaService).shareDbToNamespace(argThat(dbr -> dbr.getBgVersion() == null
                                                              && dbr.getDatabase().equals(databaseRegistry.getDatabase()) && dbr.getNamespace().equals("test-namespace-active")
                                                              && dbr.getClassifier().get(NAMESPACE).equals("test-namespace-active")), eq("test-namespace-candidate"));
    }

    @Test
    void warmupRestoreTask() {
        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace("test-namespace-active");
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setVersion("v2");
        bgNamespaceActive.setBgDomain(bgDomain);


        BgNamespace bgNamespaceIdle = new BgNamespace();
        bgNamespaceIdle.setNamespace("test-namespace-candidate");
        bgNamespaceIdle.setState(IDLE_STATE);
        bgNamespaceIdle.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceIdle));

        BgStateRequest.BGStateNamespace bgRequestedNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, "test-namespace-candidate", "v2");
        BgStateRequest.BGStateNamespace bgRequestedNamespaceActive = createBgStateNamespace(ACTIVE_STATE, "test-namespace-active", "v1");


        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-candidate")).thenReturn(Optional.of(bgNamespaceIdle));
        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-active")).thenReturn(Optional.of(bgNamespaceActive));

        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgRequestedNamespaceActive);
        bgState.setPeerNamespace(bgRequestedNamespaceCandidate);

        BgTrack bgTrack = new BgTrack();
        String bgTrackId = UUID.randomUUID().toString();
        bgTrack.setId(bgTrackId);

        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        when(processInstance.getState()).thenReturn(TaskState.TERMINATED);

        when(processService.getProcess(bgTrackId)).thenReturn(processInstance);

        Optional<BgTrack> bgTrackOptional = Optional.of(bgTrack);
        when(bgTrackRepository.findByNamespaceAndOperation(any(), eq(WARMUP_OPERATION))).thenReturn(bgTrackOptional);

        ProcessInstanceImpl warmupResult = blueGreenService.warmup(bgState);
        Assertions.assertEquals(warmupResult, processInstance);

        Mockito.verify(processService).retryProcess(processInstance);
    }

    @Test
    void warmupBgTrackNotFoundLazy() {
        when(balancingRulesService.copyNamespaceRule(any(), any())).thenReturn(null);
        when(balancingRulesService.copyMicroserviceRule(any(), any())).thenReturn(null);
        when(databaseRolesService.copyDatabaseRole(any(), any())).thenReturn(null);
        when(declarativeDbaasCreationService.findAllByNamespace("test-namespace-active")).thenReturn(Collections.emptyList());

        BgTrack bgTrack = new BgTrack();
        String bgTrackId = UUID.randomUUID().toString();
        bgTrack.setId(bgTrackId);

        when(processService.getProcess(bgTrackId)).thenReturn(null);

        Optional<BgTrack> bgTrackOptional = Optional.of(bgTrack);
        when(bgTrackRepository.findByNamespaceAndOperation(any(), eq(WARMUP_OPERATION))).thenReturn(bgTrackOptional);
        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace("test-namespace-active");
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setVersion("v2");
        bgNamespaceActive.setBgDomain(bgDomain);

        BgNamespace bgNamespaceIdle = new BgNamespace();
        bgNamespaceIdle.setNamespace("test-namespace-candidate");
        bgNamespaceIdle.setState(IDLE_STATE);
        bgNamespaceIdle.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceIdle));


        BgStateRequest.BGStateNamespace bgRequestedNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, "test-namespace-candidate", "v2");
        BgStateRequest.BGStateNamespace bgRequestedNamespaceActive = createBgStateNamespace(ACTIVE_STATE, "test-namespace-active", "v1");

        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgRequestedNamespaceActive);
        bgState.setPeerNamespace(bgRequestedNamespaceCandidate);

        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-candidate")).thenReturn(Optional.of(bgNamespaceIdle));
        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-active")).thenReturn(Optional.of(bgNamespaceActive));
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        SortedMap<String, Object> classifier = createClassifier("test", "test-namespace-active");
        DatabaseRegistry databaseRegistry = createDatabaseRegistry(classifier, "postgresql", "123", "username", "dbName");
        Database database = databaseRegistry.getDatabase();
        SortedMap<String, Object> warmupedClassifier = createClassifier("test", "test-namespace-candidate");
        DatabaseRegistry warmupedDatabaseRegistry = createDatabaseRegistry(warmupedClassifier, "postgresql", "123", "username", "dbName");
        warmupedDatabaseRegistry.setDatabase(database);
        database.getDatabaseRegistry().add(warmupedDatabaseRegistry);

        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace-active")).thenReturn(List.of(databaseRegistry));

        blueGreenService.warmup(bgState);
        verify(balancingRulesService, times(1)).copyNamespaceRule("test-namespace-active", "test-namespace-candidate");
        verify(balancingRulesService, times(1)).copyMicroserviceRule("test-namespace-active", "test-namespace-candidate");
        verify(databaseRolesService, times(1)).copyDatabaseRole("test-namespace-active", "test-namespace-candidate");
        verify(declarativeDbaasCreationService, times(1)).findAllByNamespace("test-namespace-active");
        verify(databaseRegistryDbaasRepository).findAnyLogDbRegistryTypeByNamespace("test-namespace-active");
        verify(databaseRegistryDbaasRepository, times(0)).saveAnyTypeLogDb(argThat(dbr -> dbr.getBgVersion() == null
                && dbr.getDatabase().equals(databaseRegistry.getDatabase()) && dbr.getNamespace().equals("test-namespace-candidate")
                && dbr.getClassifier().get(NAMESPACE).equals("test-namespace-candidate")));
    }

    @Test
    void warmup() {
        when(balancingRulesService.copyNamespaceRule(any(), any())).thenReturn(null);
        when(balancingRulesService.copyMicroserviceRule(any(), any())).thenReturn(null);
        when(databaseRolesService.copyDatabaseRole(any(), any())).thenReturn(null);

        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("test-microserviceName", "test-namespace-active");
        DatabaseDeclarativeConfig databaseDeclarativeConfig2 = createDatabaseDeclarativeConfig("test-microserviceName", "test-namespace-active");
        databaseDeclarativeConfig2.getClassifier().put("customKey", "test");
        DatabaseDeclarativeConfig databaseDeclarativeConfigCandidate = createDatabaseDeclarativeConfig("test-microserviceName", "test-namespace-candidate");
        DatabaseDeclarativeConfig databaseDeclarativeConfigCandidate2 = createDatabaseDeclarativeConfig("test-microserviceName", "test-namespace-candidate");
        databaseDeclarativeConfigCandidate2.getClassifier().put("customKey", "test");
        when(declarativeDbaasCreationService.saveConfigurationWithNewNamespace(any(), eq("test-namespace-candidate"))).then(invocationOnMock -> {
            DatabaseDeclarativeConfig declarativeConfig = invocationOnMock.getArgument(0);
            if (!declarativeConfig.getClassifier().containsKey("customKey"))
                return databaseDeclarativeConfigCandidate;
            else
                return databaseDeclarativeConfigCandidate2;
        });
        when(declarativeDbaasCreationService.findAllByNamespace("test-namespace-active")).thenReturn(Arrays.asList(databaseDeclarativeConfig, databaseDeclarativeConfig2));
        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace("test-namespace-active");
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setVersion("v2");
        bgNamespaceActive.setBgDomain(bgDomain);

        BgNamespace bgNamespaceIdle = new BgNamespace();
        bgNamespaceIdle.setNamespace("test-namespace-candidate");
        bgNamespaceIdle.setState(IDLE_STATE);
        bgNamespaceIdle.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceIdle));

        BgStateRequest.BGStateNamespace bgRequestedNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, "test-namespace-candidate", "v2");
        BgStateRequest.BGStateNamespace bgRequestedNamespaceActive = createBgStateNamespace(ACTIVE_STATE, "test-namespace-active", "v1");

        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgRequestedNamespaceActive);
        bgState.setPeerNamespace(bgRequestedNamespaceCandidate);

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-candidate")).thenReturn(Optional.of(bgNamespaceIdle));
        when(bgNamespaceRepository.findBgNamespaceByNamespace("test-namespace-active")).thenReturn(Optional.of(bgNamespaceActive));
        when(databaseConfigurationCreationService.isAllDatabaseExists(any(), any())).then(invocationOnMock -> {
            DatabaseDeclarativeConfig declarativeConfig = invocationOnMock.getArgument(0);
            return new DatabaseConfigurationCreationService.DatabaseExistence(true, declarativeConfig.getClassifier().containsKey("customKey"));
        });

        blueGreenService.warmup(bgState);
        verify(balancingRulesService, times(1)).copyNamespaceRule("test-namespace-active", "test-namespace-candidate");
        verify(balancingRulesService, times(1)).copyMicroserviceRule("test-namespace-active", "test-namespace-candidate");
        verify(databaseRolesService, times(1)).copyDatabaseRole("test-namespace-active", "test-namespace-candidate");
        verify(declarativeDbaasCreationService, times(1)).findAllByNamespace("test-namespace-active");
        verify(databaseConfigurationCreationService, times(1)).isAllDatabaseExists(databaseDeclarativeConfigCandidate, "test-namespace-active");
        verify(databaseConfigurationCreationService, times(1)).isAllDatabaseExists(databaseDeclarativeConfigCandidate2, "test-namespace-active");
    }

    @Test
    void warmupWrongStates() {
        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace(NS_1);
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setBgDomain(bgDomain);
        BgNamespace bgNamespaceIdle = new BgNamespace();
        bgNamespaceIdle.setNamespace(NS_2);
        bgNamespaceIdle.setState(LEGACY_STATE);
        bgNamespaceIdle.setBgDomain(bgDomain);
        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceIdle));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespaceActive));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespaceIdle));

        BgStateRequest.BGStateNamespace bgRequestedNamespaceActive = createBgStateNamespace(ACTIVE_STATE, NS_1, null);
        BgStateRequest.BGStateNamespace bgRequestedNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, NS_2, null);
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgRequestedNamespaceActive);
        bgState.setPeerNamespace(bgRequestedNamespaceCandidate);
        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.warmup(bgState));
    }

    private DatabaseDeclarativeConfig createDatabaseDeclarativeConfig(String microserviceName, String namespace) {
        DatabaseDeclarativeConfig result = new DatabaseDeclarativeConfig();
        result.setType("postgresql");

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("scope", "service");
        classifier.put("microserviceName", microserviceName);
        classifier.put("namespace", namespace);
        result.setNamespace(namespace);
        result.setClassifier(classifier);
        return result;
    }

    @Test
    void createOrUpdateDatabaseLazy() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setLazy(true);
        Response response = blueGreenService.createOrUpdateDatabaseWarmup(databaseDeclarativeConfig, null);
        Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Assertions.assertFalse(response.hasEntity());
    }

    @Test
    void createOrUpdateDatabaseWithRetry() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setClassifier(createClassifier("123", NAMESPACE));
        databaseDeclarativeConfig.setType("postgresql");
        databaseDeclarativeConfig.setLazy(false);
        Mockito.when(aggregatedDatabaseAdministrationService.createDatabaseFromRequest(any(), any(), any(), any(), ArgumentMatchers.isNull())).thenThrow(new RuntimeException("Ex"));
        Assertions.assertThrows(RuntimeException.class, () -> blueGreenService.createOrUpdateDatabaseWarmup(databaseDeclarativeConfig, null));

        verify(aggregatedDatabaseAdministrationService, times(5)).createDatabaseFromRequest(any(), any(), any(), any(), ArgumentMatchers.isNull());
    }

    @Test
    void createOrUpdateDatabaseNotLazy() {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = new DatabaseDeclarativeConfig();
        databaseDeclarativeConfig.setLazy(false);
        databaseDeclarativeConfig.setType("postgresql");
        databaseDeclarativeConfig.setClassifier(createClassifier("test", NAMESPACE));
        Mockito.when(aggregatedDatabaseAdministrationService.createDatabaseFromRequest(any(), any(), any(), any(), any())).thenReturn(Response.status(201).build());
        Response response = blueGreenService.createOrUpdateDatabaseWarmup(databaseDeclarativeConfig, null);
        Assertions.assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        Assertions.assertFalse(response.hasEntity());
    }

    @Test
    void initBgDomain() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.empty());
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.empty());
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        blueGreenService.initBgDomain(bgStateRequest);
        verify(aggregatedDatabaseAdministrationService, times(0)).createDatabaseFromRequest(any(), any(), any(), any(), any());

        verify(bgDomainRepository, times(1)).persist(any(BgDomain.class));
    }

    @Test
    void initBgDomainWithUpdateDatabases() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("microserviceName", NS_1);
        databaseDeclarativeConfig.setVersioningType(VERSION_STATE);
        when(declarativeDbaasCreationService.findAllByNamespace(NS_1)).thenReturn(List.of(databaseDeclarativeConfig));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.empty());
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.empty());
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        DatabaseRegistry dbr = createDatabaseRegistry(createClassifier(null, NS_1), "postgresql", "123", "username", "dbName");
        DatabaseRegistry dbrPeer = createDatabaseRegistry(createClassifier(null, NS_2), "postgresql", "123", "username", "dbNameDropped");
        dbrPeer.setMarkedForDrop(true);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_1)).thenReturn(List.of(dbr));
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_2)).thenReturn(List.of(dbrPeer));

        blueGreenService.initBgDomain(bgStateRequest);

        verify(databaseRegistryDbaasRepository).saveAnyTypeLogDb(dbr);
        verify(bgDomainRepository).persist(any(BgDomain.class));
    }

    @Test
    void initBgDomainWithoutUpdateDatabases() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("ms1", NS_1);
        databaseDeclarativeConfig.setVersioningType(STATIC_STATE);
        when(declarativeDbaasCreationService.findAllByNamespace(NS_1)).thenReturn(List.of(databaseDeclarativeConfig));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.empty());
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.empty());
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        blueGreenService.initBgDomain(bgStateRequest);

        verify(aggregatedDatabaseAdministrationService, times(0)).createDatabaseFromRequest(any(), any(), any(), any(), any());
        verify(bgDomainRepository).persist(any(BgDomain.class));
    }

    @Test
    void initBgDomainWithDBsInPeer() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.empty());
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.empty());
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        DatabaseRegistry dbr = createDatabaseRegistry(createClassifier(null, NS_2), "postgresql", "123", "username", "dbName");
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NS_2)).thenReturn(List.of(dbr));

        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.initBgDomain(bgStateRequest));
    }

    @Test
    void initBgDomainWithEmptyNamespaceName() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, ""), createBgStateNamespace(IDLE_STATE, ""), "");

        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.initBgDomain(bgStateRequest));
    }

    @Test
    void initBgDomainUpdateNamespaceNames() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(ACTIVE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(IDLE_STATE);
        bgDomain.setControllerNamespace("");
        bgDomain.setOriginNamespace("");
        bgDomain.setPeerNamespace("");
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));

        blueGreenService.initBgDomain(bgStateRequest);

        verify(bgDomainRepository, times(1)).persist(ArgumentMatchers.<BgDomain>argThat(domain ->
                domain.getControllerNamespace().equals(NS_C) && domain.getOriginNamespace().equals(NS_1) && domain.getPeerNamespace().equals(NS_2)
        ));
    }

    @Test
    void initBgDomainExistNamespacesWithDifferentStates() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));
        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(ACTIVE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(CANDIDATE_STATE);
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));


        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.initBgDomain(bgStateRequest));

        bgNamespace2.setState(IDLE_STATE);
        bgNamespace1.setVersion("1");
        bgNamespace2.setVersion("1");
        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.initBgDomain(bgStateRequest));
    }

    @NotNull
    private static BgStateRequest getBgStateRequest(BgStateRequest.BGStateNamespace originNamespace, BgStateRequest.BGStateNamespace peerNamespace) {
        return getBgStateRequest(originNamespace, peerNamespace, NS_C);
    }

    @NotNull
    private static BgStateRequest getBgStateRequest(BgStateRequest.BGStateNamespace originNamespace, BgStateRequest.BGStateNamespace peerNamespace, String controllerNamespace) {
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(originNamespace);
        bgState.setPeerNamespace(peerNamespace);
        bgState.setControllerNamespace(controllerNamespace);
        bgStateRequest.setBGState(bgState);
        return bgStateRequest;
    }


    private static BgStateRequest.BGStateNamespace createBgStateNamespace(String state, String namespace, String version) {
        BgStateRequest.BGStateNamespace bgNamespace = new BgStateRequest.BGStateNamespace();
        bgNamespace.setState(state);
        bgNamespace.setName(namespace);
        bgNamespace.setVersion(version);
        return bgNamespace;
    }

    private static BgStateRequest.BGStateNamespace createBgStateNamespace(String state, String namespace) {
        return createBgStateNamespace(state, namespace, null);
    }

    @Test
    void initBgDomainExceptionState() {
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace1 = createBgStateNamespace(ACTIVE_STATE, NS_1);
        BgStateRequest.BGStateNamespace bgNamespace2 = createBgStateNamespace("differentState", NS_2);
        bgState.setOriginNamespace(bgNamespace1);
        bgState.setPeerNamespace(bgNamespace2);
        bgState.setControllerNamespace(NS_C);
        bgStateRequest.setBGState(bgState);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            blueGreenService.initBgDomain(bgStateRequest);
        });

        Assertions.assertTrue(exception.getMessage().contains("States of bgRequest must be active and idle, but were active and differentState"));
        Assertions.assertTrue(exception.getMessage().contains("CORE-DBAAS-4037"));
    }

    @Test
    void initBgDomainAlreadyExistsOnlyOneNamespace() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setNamespace(NS_1);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            blueGreenService.initBgDomain(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("One of requested namespaces already used in another bgDomain"));
        Assertions.assertTrue(exception.getMessage().contains("CORE-DBAAS-4037"));
    }

    @Test
    void initBgDomainDifferentDomains() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        BgDomain bgDomain1 = new BgDomain();
        bgDomain1.setId(UUID.randomUUID());
        BgNamespace bgNamespace3 = new BgNamespace();
        bgNamespace3.setNamespace(NS_1);
        bgNamespace3.setBgDomain(bgDomain1);

        BgDomain bgDomain2 = new BgDomain();
        bgDomain2.setId(UUID.randomUUID());
        BgNamespace bgNamespace4 = new BgNamespace();
        bgNamespace4.setNamespace(NS_2);
        bgNamespace4.setBgDomain(bgDomain2);

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace3));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace4));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            blueGreenService.initBgDomain(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("These namespaces already belongs to different bgDomains"));
        Assertions.assertTrue(exception.getMessage().contains("CORE-DBAAS-4037"));
    }

    @Test
    void initBgDomainAlreadyExistsBothNamespaces() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace3 = new BgNamespace();
        bgNamespace3.setNamespace(NS_1);
        bgNamespace3.setState(ACTIVE_STATE);
        bgNamespace3.setBgDomain(bgDomain);
        BgNamespace bgNamespace4 = new BgNamespace();
        bgNamespace4.setNamespace(NS_2);
        bgNamespace4.setState(IDLE_STATE);
        bgNamespace4.setBgDomain(bgDomain);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace3, bgNamespace4));
        bgDomain.setControllerNamespace(NS_C);
        bgDomain.setOriginNamespace(NS_1);
        bgDomain.setPeerNamespace(NS_2);
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace3));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace4));


        blueGreenService.initBgDomain(bgStateRequest);
        verify(bgDomainRepository, times(0)).persist(any(BgDomain.class));
    }

    @Test
    void testCommitScenario() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(ACTIVE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(CANDIDATE_STATE);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        blueGreenService.commit(bgStateRequest);
        verify(declarativeDbaasCreationService).deleteDeclarativeConfigurationByNamespace(NS_2);
        Assertions.assertEquals(ACTIVE_STATE, bgNamespace1.getState());
        Assertions.assertEquals(IDLE_STATE, bgNamespace2.getState());
    }

    @Test
    void testCommitWrongStates() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(IDLE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(ACTIVE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(IDLE_STATE);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));
        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.commit(bgStateRequest));
    }

    @Test
    void testRestoreDatabase() throws NamespaceRestorationFailedException {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("ms1", NS_1);
        UUID backupId = UUID.randomUUID();
        UUID restoreId = UUID.randomUUID();
        NamespaceBackup namespaceBackup = new NamespaceBackup();

        NamespaceRestoration namespaceRestoration = new NamespaceRestoration();
        RestoreResult restoreResult = new RestoreResult();
        DatabasesBackup databasesBackup = new DatabasesBackup();
        databasesBackup.setDatabases(List.of("db1"));
        databasesBackup.setAdapterId("adapterId");
        restoreResult.setDatabasesBackup(databasesBackup);
        Map<String, String> changedName = new HashMap<>();
        changedName.put("db1", "db2");

        restoreResult.setChangedNameDb(changedName);
        namespaceRestoration.setRestoreResults(List.of(restoreResult));

        when(backupsService.restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()), eq(true),
                eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap())).thenReturn(namespaceRestoration);

        DatabaseDbaasRepository databaseDbaasRepository = mock(DatabaseDbaasRepository.class);
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(databaseDbaasRepository);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(backupsService.deleteRestore(eq(namespaceBackup.getId()), any())).thenReturn(namespaceBackup);

        blueGreenService.restoreDatabase(databaseDeclarativeConfig, "v1", backupId, restoreId, namespaceBackup.getId(), new HashMap<>());

        verify(databaseDbaasRepository, times(1)).findByNameAndAdapterId("db2", "adapterId");
    }

    @Test
    void testRestoreDatabaseWithRetry() throws NamespaceRestorationFailedException {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("ms1", NS_1);
        UUID backupId = UUID.randomUUID();
        UUID restoreId = UUID.randomUUID();
        NamespaceBackup namespaceBackup = new NamespaceBackup();
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(backupsService.restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()), eq(true),
                eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap())).thenThrow(new RuntimeException());
        when(backupsService.deleteRestore(eq(namespaceBackup.getId()), any())).thenReturn(namespaceBackup);

        Assertions.assertThrows(BackupExecutionException.class, () -> blueGreenService.restoreDatabase(databaseDeclarativeConfig, "v1", backupId, restoreId, namespaceBackup.getId(), new HashMap<>()));
        verify(backupsService, times(5)).restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()),
                eq(true), eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap());
    }

    @Test
    void testRestoreWithException() throws NamespaceRestorationFailedException {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("ms1", NS_1);
        UUID backupId = UUID.randomUUID();
        UUID restoreId = UUID.randomUUID();
        NamespaceBackup namespaceBackup = new NamespaceBackup();
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(any(), any())).thenReturn(Optional.of(new DatabaseRegistry()));


        Assertions.assertThrows(DBCreationConflictException.class, () -> blueGreenService.restoreDatabase(databaseDeclarativeConfig, "v1", backupId, restoreId, namespaceBackup.getId(), new HashMap<>()));
        verify(backupsService, times(0)).restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()),
                eq(true), eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap());
    }

    @Test
    void testRestoreFailDatabaseWithRetry() throws NamespaceRestorationFailedException {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("ms1", NS_1);
        UUID backupId = UUID.randomUUID();
        UUID restoreId = UUID.randomUUID();
        NamespaceBackup namespaceBackup = new NamespaceBackup();
        NamespaceRestoration namespaceRestoration = mock(NamespaceRestoration.class);
        when(namespaceRestoration.getStatus()).thenReturn(Status.FAIL);
        when(backupsService.restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()), eq(true),
                eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap())).thenReturn(namespaceRestoration);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(backupsService.deleteRestore(eq(namespaceBackup.getId()), any())).thenReturn(namespaceBackup);

        Assertions.assertThrows(BackupExecutionException.class, () -> blueGreenService.restoreDatabase(databaseDeclarativeConfig, "v1", backupId, restoreId, namespaceBackup.getId(), new HashMap<>()));

        verify(backupsService, times(5)).restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()),
                eq(true), eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap());
    }

    @Test
    void testBackupDatabaseWithRetry() {
        UUID backupId = UUID.randomUUID();
        UUID databaseId = UUID.randomUUID();

        when(backupsService.collectBackupSingleDatabase(NAMESPACE, backupId, true, databaseId)).thenThrow(new RuntimeException());

        Assertions.assertThrows(RuntimeException.class, () -> blueGreenService.createDatabaseBackup(backupId, NAMESPACE, databaseId));

        verify(backupsService, times(5)).collectBackupSingleDatabase(NAMESPACE, backupId, true, databaseId);
    }

    @Test
    void testBackupDatabaseFailWithRetry() {
        UUID backupId = UUID.randomUUID();
        UUID databaseId = UUID.randomUUID();
        NamespaceBackup backup = mock(NamespaceBackup.class);
        when(backup.getStatus()).thenReturn(NamespaceBackup.Status.FAIL);
        when(backupsService.collectBackupSingleDatabase(NAMESPACE, backupId, true, databaseId)).thenReturn(backup);

        Assertions.assertThrows(RuntimeException.class, () -> blueGreenService.createDatabaseBackup(backupId, NAMESPACE, databaseId));

        verify(backupsService, times(5)).collectBackupSingleDatabase(NAMESPACE, backupId, true, databaseId);
    }

    @Test
    void testDeleteBackupWithRetry() {
        UUID backupId = UUID.randomUUID();
        NamespaceBackup backup = mock(NamespaceBackup.class);
        when(backupsDbaasRepository.findById(backupId)).thenReturn(Optional.of(backup));

        when(backupsService.deleteBackup(backup)).thenThrow(new RuntimeException());

        Assertions.assertThrows(RuntimeException.class, () -> blueGreenService.deleteBackup(backupId));

        verify(backupsService, times(5)).deleteBackup(backup);
    }

    @Test
    void testRestoreDatabaseException() throws NamespaceRestorationFailedException {
        DatabaseDeclarativeConfig databaseDeclarativeConfig = createDatabaseDeclarativeConfig("ms1", NS_1);
        UUID backupId = UUID.randomUUID();
        UUID restoreId = UUID.randomUUID();
        NamespaceBackup namespaceBackup = new NamespaceBackup();
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(backupsService.restore(eq(namespaceBackup), eq(restoreId), eq(databaseDeclarativeConfig.getNamespace()), eq(true),
                eq("v1"), eq(databaseDeclarativeConfig.getClassifier()), anyMap())).thenThrow(new RuntimeException());

        Assertions.assertThrows(BackupExecutionException.class, () -> blueGreenService.restoreDatabase(databaseDeclarativeConfig, "v1", backupId, restoreId, namespaceBackup.getId(), new HashMap<>()));
    }


    @Test
    void testDestroyDomainException() {
        Set<String> bgStateRequest = Set.of("test-namespace-active", "test-namespace-candidate");
        Assertions.assertThrows(RuntimeException.class, () -> blueGreenService.destroyDomain(bgStateRequest));

        verify(bgDomainRepository, times(0)).delete(any());
    }

    @Test
    void testDestroyDomainStillHaveDatabases() {

        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace("test-namespace-active");
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setVersion("v2");
        bgNamespaceActive.setBgDomain(bgDomain);

        BgNamespace bgNamespaceCandidate = new BgNamespace();
        bgNamespaceCandidate.setNamespace("test-namespace-candidate");
        bgNamespaceCandidate.setState(IDLE_STATE);
        bgNamespaceCandidate.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceCandidate));

        Set<String> bgStateRequest = Set.of("test-namespace-active", "test-namespace-candidate");

        doReturn(Optional.of(bgNamespaceActive)).when(bgNamespaceRepository).findBgNamespaceByNamespace(ArgumentMatchers
                .argThat(o -> o.equals(bgNamespaceActive.getNamespace()) || o.equals(bgNamespaceCandidate.getNamespace())));


        DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository = mock(DatabaseRegistryDbaasRepository.class);

        SortedMap<String, Object> classifier = createClassifier("test", "test-namespace-candidate");
        DatabaseRegistry databaseRegistry = createDatabaseRegistry(classifier, "postgresql", "123", "username", "dbName");
        List<DatabaseRegistry> databaseRegistryToDelete = List.of(databaseRegistry);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace-candidate")).thenReturn(databaseRegistryToDelete);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace-active")).thenReturn(new ArrayList<>());

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        Assertions.assertThrows(BgNamespaceNotEmptyException.class, () -> blueGreenService.destroyDomain(bgStateRequest));

        verify(bgDomainRepository, times(0)).delete(bgDomain);
    }

    @Test
    void testDestroyDomainStillHaveMarkedForDropDatabases() {
        BgDomain bgDomain = new BgDomain();

        BgNamespace bgNamespaceActive = new BgNamespace();
        bgNamespaceActive.setNamespace("test-namespace-active");
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setVersion("v2");
        bgNamespaceActive.setBgDomain(bgDomain);

        BgNamespace bgNamespaceCandidate = new BgNamespace();
        bgNamespaceCandidate.setNamespace("test-namespace-candidate");
        bgNamespaceCandidate.setState(IDLE_STATE);
        bgNamespaceCandidate.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceCandidate));

        Set<String> bgStateRequest = Set.of("test-namespace-active", "test-namespace-candidate");

        doReturn(Optional.of(bgNamespaceActive)).when(bgNamespaceRepository).findBgNamespaceByNamespace(ArgumentMatchers
                .argThat(o -> o.equals(bgNamespaceActive.getNamespace()) || o.equals(bgNamespaceCandidate.getNamespace())));


        DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository = mock(DatabaseRegistryDbaasRepository.class);

        SortedMap<String, Object> classifier = createClassifier("test", "test-namespace-candidate");
        DatabaseRegistry databaseRegistry = createDatabaseRegistry(classifier, "postgresql", "123", "username", "dbName");
        databaseRegistry.setMarkedForDrop(true);
        List<DatabaseRegistry> databaseRegistryToDelete = List.of(databaseRegistry);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace-candidate")).thenReturn(databaseRegistryToDelete);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace("test-namespace-active")).thenReturn(new ArrayList<>());

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        Assertions.assertDoesNotThrow(() -> blueGreenService.destroyDomain(bgStateRequest));
        verify(bgDomainRepository, times(1)).delete(bgDomain);
    }


    @Test
    void testDestroyDomain() {
        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespaceActive = createBgNamespace("test-namespace-active", ACTIVE_STATE, "v2");
        bgNamespaceActive.setBgDomain(bgDomain);
        BgNamespace bgNamespaceCandidate = createBgNamespace("test-namespace-candidate", IDLE_STATE);
        bgNamespaceCandidate.setBgDomain(bgDomain);

        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceCandidate));

        Set<String> bgStateRequest = Set.of("test-namespace-active", "test-namespace-candidate");

        doReturn(Optional.of(bgNamespaceActive)).when(bgNamespaceRepository).findBgNamespaceByNamespace(ArgumentMatchers
                .argThat(o -> o.equals(bgNamespaceActive.getNamespace()) || o.equals(bgNamespaceCandidate.getNamespace())));

        DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository = mock(DatabaseRegistryDbaasRepository.class);

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        blueGreenService.destroyDomain(bgStateRequest);

        verify(bgDomainRepository).delete(bgDomain);
    }

    @Test
    void testDestroyDomainNotFound() {
        Set<String> bgStateRequest = Set.of("test-namespace-active", "test-namespace-candidate");

        assertThrows(BgDomainNotFoundException.class, () -> blueGreenService.destroyDomain(bgStateRequest));
        verify(bgDomainRepository, times(0)).delete(any());
    }

    @Test
    void testDestroyDomainIncorrectNamespaces() {
        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespaceActive = createBgNamespace("test-namespace-active", ACTIVE_STATE, "v2");
        bgNamespaceActive.setBgDomain(bgDomain);
        BgNamespace bgNamespaceCandidate = createBgNamespace("test-namespace-candidate", IDLE_STATE);
        bgNamespaceCandidate.setBgDomain(bgDomain);
        bgDomain.setNamespaces(Arrays.asList(bgNamespaceActive, bgNamespaceCandidate));

        doReturn(Optional.of(bgNamespaceActive)).when(bgNamespaceRepository).findBgNamespaceByNamespace(ArgumentMatchers
                .argThat(o -> o.equals(bgNamespaceActive.getNamespace())));

        SortedSet<String> bgStateRequest = new TreeSet<>();// Set.of("test-namespace-active", "test-namespace-incorrect-candidate");
        bgStateRequest.add("test-namespace-active");
        bgStateRequest.add("test-namespace-incorrect-candidate");
        assertThrows(BgRequestValidationException.class, () -> blueGreenService.destroyDomain(bgStateRequest));
        verify(bgDomainRepository, times(0)).delete(any());
    }


    @Test
    void testPromote() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(LEGACY_STATE, NS_1), createBgStateNamespace(ACTIVE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(ACTIVE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(CANDIDATE_STATE);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));

        BgNamespace bgNamespaceUpdate1 = new BgNamespace();
        bgNamespaceUpdate1.setNamespace(NS_1);
        bgNamespaceUpdate1.setBgDomain(bgDomain);
        bgNamespaceUpdate1.setState(LEGACY_STATE);
        BgNamespace bgNamespaceUpdate2 = new BgNamespace();
        bgNamespaceUpdate2.setNamespace(NS_2);
        bgNamespaceUpdate2.setBgDomain(bgDomain);
        bgNamespaceUpdate2.setState(ACTIVE_STATE);

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));
        blueGreenService.promote(bgStateRequest);
        verify(bgNamespaceRepository, times(1)).persist(bgNamespaceUpdate1);
        verify(bgNamespaceRepository, times(1)).persist(bgNamespaceUpdate2);
    }

    @Test
    void testPromoteWrongStates() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(LEGACY_STATE, NS_1), createBgStateNamespace(ACTIVE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(ACTIVE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(IDLE_STATE);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));
        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.promote(bgStateRequest));
    }

    @Test
    void testRollback() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(CANDIDATE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(LEGACY_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(ACTIVE_STATE);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));

        BgNamespace bgNamespaceUpdate1 = new BgNamespace();
        bgNamespaceUpdate1.setNamespace(NS_1);
        bgNamespaceUpdate1.setBgDomain(bgDomain);
        bgNamespaceUpdate1.setState(ACTIVE_STATE);
        BgNamespace bgNamespaceUpdate2 = new BgNamespace();
        bgNamespaceUpdate2.setNamespace(NS_2);
        bgNamespaceUpdate2.setBgDomain(bgDomain);
        bgNamespaceUpdate2.setState(CANDIDATE_STATE);

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));
        blueGreenService.rollback(bgStateRequest);
        verify(bgNamespaceRepository, times(1)).persist(bgNamespaceUpdate1);
        verify(bgNamespaceRepository, times(1)).persist(bgNamespaceUpdate2);
    }

    @Test
    void testRollbackWrongStates() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, NS_1), createBgStateNamespace(CANDIDATE_STATE, NS_2));

        BgDomain bgDomain = new BgDomain();
        BgNamespace bgNamespace1 = new BgNamespace();
        bgNamespace1.setNamespace(NS_1);
        bgNamespace1.setBgDomain(bgDomain);
        bgNamespace1.setState(CANDIDATE_STATE);
        BgNamespace bgNamespace2 = new BgNamespace();
        bgNamespace2.setNamespace(NS_2);
        bgNamespace2.setBgDomain(bgDomain);
        bgNamespace2.setState(ACTIVE_STATE);
        bgDomain.setNamespaces(Arrays.asList(bgNamespace1, bgNamespace2));

        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_1)).thenReturn(Optional.of(bgNamespace1));
        when(bgNamespaceRepository.findBgNamespaceByNamespace(NS_2)).thenReturn(Optional.of(bgNamespace2));
        Assertions.assertThrows(BgRequestValidationException.class, () -> blueGreenService.rollback(bgStateRequest));
    }

    @Test
    void testCommitDatabasesByNamespace() throws InterruptedException {
        DatabaseRegistry dbRegistryVersioned = createDatabaseRegistry(createClassifier("test", NAMESPACE), "postgresql", "adapter", "username", "dbName");
        dbRegistryVersioned.setId(UUID.randomUUID());
        SortedMap<String, Object> test = createClassifier("test", NAMESPACE);
        when(databaseRegistryDbaasRepository.findAllVersionedDatabaseRegistries(NAMESPACE)).thenReturn(Collections.singletonList(dbRegistryVersioned));

        DatabaseRegistry dbRegistryStaticObsolete = createDatabaseRegistry(test, "postgresql", "adapter", "username", "dbName");
        dbRegistryStaticObsolete.setId(UUID.randomUUID());
        DatabaseRegistry dbRegistryStaticActual = createDatabaseRegistry(test, "postgresql", "adapter", "username", "dbName");
        dbRegistryStaticActual.setId(UUID.randomUUID());
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setTimeDbCreation(new Date());
        databaseRegistry.setNamespace(NAMESPACE + "_active");
        databaseRegistry.setClassifier(new TreeMap<>(createClassifier("test", NAMESPACE + "_active")));
        databaseRegistry.setType("postgresql");
        dbRegistryStaticActual.getDatabaseRegistry().add(databaseRegistry);
        when(databaseRegistryDbaasRepository.findAllTransactionalDatabaseRegistries(NAMESPACE)).thenReturn(List.of(dbRegistryStaticObsolete, dbRegistryStaticActual));

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        blueGreenService.commitDatabasesByNamespace(NAMESPACE);
        Thread.sleep(1000);
        Mockito.verify(databaseRegistryDbaasRepository, times(1)).findAllVersionedDatabaseRegistries(NAMESPACE);
        Mockito.verify(databaseRegistryDbaasRepository, times(1)).findAllTransactionalDatabaseRegistries(NAMESPACE);
        Mockito.verify(dBaaService, times(1)).markVersionedDatabasesAsOrphan(List.of(dbRegistryVersioned));
        Mockito.verify(dBaaService, times(1)).markDatabasesAsOrphan(dbRegistryStaticObsolete);
        Mockito.verify(dBaaService, times(1)).dropDatabasesAsync(NAMESPACE, List.of(dbRegistryVersioned, dbRegistryStaticObsolete));
        verify(declarativeDbaasCreationService).deleteDeclarativeConfigurationByNamespace(NAMESPACE);
    }


    @Test
    void testGetOrphanDatabases() {
        DatabaseRegistry dbRegistryVersioned = createDatabaseRegistry(createClassifier("test", NAMESPACE), "postgresql", "adapter", "username", "dbName");
        UUID versionedId = UUID.randomUUID();
        dbRegistryVersioned.setId(versionedId);
        dbRegistryVersioned.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        SortedMap<String, Object> test = createClassifier("test", NAMESPACE);
        test.put(MARKED_FOR_DROP, MARKED_FOR_DROP);
        DatabaseRegistry dbRegistryStatic = createDatabaseRegistry(test, "postgresql", "adapter", "username", "dbName");
        UUID staticId = UUID.randomUUID();
        dbRegistryStatic.setId(staticId);
        dbRegistryStatic.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        when(databaseDbaasRepository.findByDbState(DbState.DatabaseStateStatus.ORPHAN)).thenReturn(List.of(dbRegistryVersioned.getDatabase(), dbRegistryStatic.getDatabase()));
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(databaseDbaasRepository);

        List<DatabaseRegistry> orphanDatabases = blueGreenService.getOrphanDatabases(List.of(NAMESPACE));
        Assertions.assertEquals(2, orphanDatabases.size());
    }

    @Test
    void testDeleteOrphanDatabases() throws InterruptedException {
        DatabaseRegistry dbRegistryVersioned = createDatabaseRegistry(createClassifier("test", NAMESPACE), "postgresql", "adapter", "username", "dbName");
        UUID versionedId = UUID.randomUUID();
        dbRegistryVersioned.setId(versionedId);
        dbRegistryVersioned.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        SortedMap<String, Object> test = createClassifier("test", NAMESPACE);
        test.put(MARKED_FOR_DROP, MARKED_FOR_DROP);
        DatabaseRegistry dbRegistryStatic = createDatabaseRegistry(test, "postgresql", "adapter", "username", "dbName");
        UUID staticId = UUID.randomUUID();
        dbRegistryStatic.setId(staticId);
        dbRegistryStatic.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        when(databaseDbaasRepository.findByDbState(DbState.DatabaseStateStatus.ORPHAN)).thenReturn(List.of(dbRegistryVersioned.getDatabase(), dbRegistryStatic.getDatabase()));
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(databaseDbaasRepository);

        List<DatabaseRegistry> orphanDatabases = blueGreenService.dropOrphanDatabases(List.of(NAMESPACE), true);
        Thread.sleep(1000);
        Assertions.assertEquals(2, orphanDatabases.size());
        Mockito.verify(dBaaService).dropDatabases(any(), any());
    }

    @Test
    void testDoNotDeleteOrphanDatabases() {
        DatabaseRegistry dbRegistryVersioned = createDatabaseRegistry(createClassifier("test", NAMESPACE), "postgresql", "adapter", "username", "dbName");
        UUID versionedId = UUID.randomUUID();
        dbRegistryVersioned.setId(versionedId);
        dbRegistryVersioned.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        SortedMap<String, Object> test = createClassifier("test", NAMESPACE);
        test.put(MARKED_FOR_DROP, MARKED_FOR_DROP);
        DatabaseRegistry dbRegistryStatic = createDatabaseRegistry(test, "postgresql", "adapter", "username", "dbName");
        UUID staticId = UUID.randomUUID();
        dbRegistryStatic.setId(staticId);
        dbRegistryStatic.getDbState().setDatabaseState(DbState.DatabaseStateStatus.ORPHAN);
        when(databaseDbaasRepository.findByDbState(DbState.DatabaseStateStatus.ORPHAN)).thenReturn(List.of(dbRegistryVersioned.getDatabase(), dbRegistryStatic.getDatabase()));
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(databaseDbaasRepository);

        List<DatabaseRegistry> orphanDatabases = blueGreenService.dropOrphanDatabases(List.of(NAMESPACE), false);
        Assertions.assertEquals(2, orphanDatabases.size());
        Mockito.verifyNoInteractions(dBaaService);
    }

    BgNamespace createBgNamespace(String namespace, String state) {
        return createBgNamespace(namespace, state, null);
    }

    BgNamespace createBgNamespace(String namespace, String state, @Nullable String version) {
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setNamespace(namespace);
        bgNamespace.setState(state);
        bgNamespace.setVersion(version);
        return bgNamespace;
    }


    @NotNull
    private static ProcessInstanceImpl createTestProcessInstance(TaskState ts1, TaskState ts2) {
        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        TaskInstanceImpl task1 = mock(TaskInstanceImpl.class);
        when(task1.getState()).thenReturn(ts1);

        TaskInstanceImpl task2 = mock(TaskInstanceImpl.class);
        when(task2.getState()).thenReturn(ts2);

        when(processInstance.getTasks()).thenReturn(List.of(task1, task2));
        return processInstance;
    }


    private SortedMap<String, Object> createClassifier(String testClassifierValue, String namespace) {
        SortedMap<String, Object> classifier = new TreeMap<>();
        if (testClassifierValue != null) {
            classifier.put("test-key", testClassifierValue);
        }
        classifier.put("microserviceName", "microserviceName");
        classifier.put("scope", "service");
        classifier.put("namespace", namespace);
        return classifier;
    }

    private DatabaseRegistry createDatabaseRegistry(Map<String, Object> classifier, String type, String adapterId, String username, String dbName) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        ArrayList<Map<String, Object>> connectionProperties = new ArrayList<>(List.of(new HashMap<String, Object>() {{
            put("username", username);
            put(ROLE, Role.ADMIN.toString());
        }}));
        database.setConnectionProperties(connectionProperties);
        database.setClassifier(new TreeMap<>(classifier));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setTimeDbCreation(new Date());
        databaseRegistry.setNamespace(NAMESPACE);
        databaseRegistry.setClassifier(new TreeMap<>(classifier));
        databaseRegistry.setType(type);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        databaseRegistry.setDatabase(database);
        database.setName(dbName);
        database.setAdapterId(adapterId);
        database.setPhysicalDatabaseId(adapterId);
        database.setSettings(new HashMap<String, Object>() {{
            put("setting-one", "value-one");
        }});
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setResources(new LinkedList<>(Arrays.asList(new DbResource("username", username),
                new DbResource("database", dbName))));
        return databaseRegistry;
    }
}