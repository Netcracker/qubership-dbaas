package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;
import org.qubership.cloud.dbaas.dto.InstructionType;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseHistoryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.PhysicalDatabaseInstructionRepository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class InstructionServiceTest {

    @InjectMocks
    private InstructionService instructionService;
    @InjectMocks
    private DBaaService dBaaService = Mockito.spy(new DBaaService());
    @Mock
    private PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;

    @Mock
    private LogicalDbDbaasRepository logicalDbDbaasRepository;

    @Mock
    private DatabaseDbaasRepository databaseDbaasRepository;

    @Mock
    private DatabaseHistoryDbaasRepository databaseHistoryDbaasRepository;

    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    private PasswordEncryption encryption;

    private final String TEST_USERNAME = "test-username";
    private final String TEST_PASSWORD = "test-password";
    private final String TEST_ADAPTER_ID = "test-adapter-id";
    private final String TEST_ADAPTER_ADDRESS = "test-adapter-address";
    private final String TEST_TYPE = "test-type";
    private final String TEST_PHYDBID = "test-phydbid";
    private final String TEST_LABEL_KEY = "db_cpq_domain";
    private final String TEST_LABEL_VALUE = "cpq";
    private final String TEST_INSTRUCTION_ID = "acde070d-8c4c-4f0d-9d8a-162843c10333";

    private final String TEST_PHYDBIDENTIFIER = "test-phydbidentifier";

    private static final String NAMESPACE = "test-namespace";
    private final UUID PHYDBID = UUID.randomUUID();
    private final Map<String, String> TEST_LABELS = new HashMap<String, String>() {{
        put(TEST_LABEL_KEY, TEST_LABEL_VALUE);
    }};

    private final int PORTION_SIZE = 100;


    @Test
    void checkDiffInSupportedRolesTest() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setRoles(Collections.singletonList(Role.ADMIN.toString()));
        boolean isRolesDifferent = instructionService.isRolesDifferent(physicalDatabaseRegistryRequest.getMetadata().getSupportedRoles(),
                physicalDatabase.getRoles());
        Assertions.assertTrue(isRolesDifferent);
    }

    @Test
    void startMigrationProcedureWithOnePortionTest() {
        int count = 90;
        List<Database> logicalDatabases = generateListOfLogicalDatabase(count);
        Instruction instruction = instructionService.buildInstructionForAdditionalRoles(logicalDatabases);
        Assertions.assertEquals(instruction.getAdditionalRoles().size(), count);
    }

    @Test
    void completeMigration() throws JsonProcessingException {
        int portionCount = 2;
        Instruction testInstruction = generateInstruction(portionCount);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        PhysicalDatabaseInstruction physicalDatabaseInstruction = getPhysicalDatabaseInstructionSample(testInstruction);
        when(physicalDatabaseInstructionRepository.findByIdOptional(physicalDatabaseInstruction.getId())).thenReturn(Optional.of(physicalDatabaseInstruction));
        instructionService.completeMigrationProcedure(physicalDatabase.getId(), String.valueOf(physicalDatabaseInstruction.getId()), testInstruction);
        verify(physicalDatabasesService, times(1)).savePhysicalDatabaseWithRoles(any(), any());
        instructionService.deleteInstruction(String.valueOf(physicalDatabaseInstruction.getId()));
        verify(physicalDatabaseInstructionRepository, times(2)).deleteById(physicalDatabaseInstruction.getId());
    }

    @Test
    void findPortionTest() throws JsonProcessingException {
        int portionCount = 2;
        Instruction testInstruction = generateInstruction(portionCount);
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        PhysicalDatabaseInstruction physicalDatabaseInstruction = getPhysicalDatabaseInstructionSample(testInstruction);
        when(physicalDatabaseInstructionRepository.findByIdOptional(physicalDatabaseInstruction.getId())).thenReturn(Optional.of(physicalDatabaseInstruction));
        instructionService.saveInstruction(physicalDatabase, testInstruction, getPhysicalDatabaseRegistryRequestV3Sample());
        Instruction portionedResult = instructionService.findPortion(TEST_INSTRUCTION_ID);
        assertEquals(portionedResult.getId(), TEST_INSTRUCTION_ID);
        assertEquals(portionedResult.getAdditionalRoles().size(), PORTION_SIZE);
        assertNotNull(portionedResult);
    }

    @Test
    void saveInstructionAndFindNextAdditionalRolesTest() throws JsonProcessingException {
        Instruction instruction = generateInstruction(2);
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        PhysicalDatabaseInstruction physicalDatabaseInstruction = getPhysicalDatabaseInstructionSample(instruction);
        PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequest = getPhysicalDatabaseRegistryRequestV3Sample();
        when(physicalDatabaseInstructionRepository.findByIdOptional(physicalDatabaseInstruction.getId())).thenReturn(Optional.of(physicalDatabaseInstruction));
        instructionService.saveInstruction(physicalDatabase, instruction, physicalDatabaseRegistryRequest);
        List<AdditionalRoles> nextAdditionalRoles = instructionService.findNextAdditionalRoles(
                TEST_INSTRUCTION_ID);
        assertEquals(PORTION_SIZE, nextAdditionalRoles.size());
        verify(physicalDatabaseInstructionRepository, times(1)).persist(any(PhysicalDatabaseInstruction.class));
    }

    @Test
    void deleteInstruction() throws JsonProcessingException {
        Instruction instruction = generateInstruction(2);
        PhysicalDatabaseInstruction expectedInstructions = getPhysicalDatabaseInstructionSample(instruction);
        when(physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(instruction.getId()))).thenReturn(Optional.of(expectedInstructions));
        instructionService.deleteInstruction(instruction.getId());
        verify(physicalDatabaseInstructionRepository, times(1)).deleteById(expectedInstructions.getId());
    }

    @Test
    void testUpdateInstructionWithContext() throws JsonProcessingException {
        Instruction instruction = generateInstruction(2);
        List<AdditionalRoles> finalRolesToset = List.of(new AdditionalRoles(), new AdditionalRoles());

        PhysicalDatabaseInstruction physicalDatabaseInstruction = new PhysicalDatabaseInstruction();
        Optional<PhysicalDatabaseInstruction> optionalPhysicalDatabaseInstruction = Optional.of(physicalDatabaseInstruction);

        when(physicalDatabaseInstructionRepository.findByIdOptional(UUID.fromString(instruction.getId()))).thenReturn(optionalPhysicalDatabaseInstruction);
        instructionService.updateInstructionWithContext(instruction, finalRolesToset);
        verify(physicalDatabaseInstructionRepository).findByIdOptional(UUID.fromString(instruction.getId()));
        verify(physicalDatabaseInstructionRepository).persist(physicalDatabaseInstruction);
    }

    @Test
    void findInstructionByPhysicalDbID() throws JsonProcessingException {
        Instruction instruction = generateInstruction(2);
        PhysicalDatabase physicalDatabase = getPhysicalDatabaseSample();
        PhysicalDatabaseInstruction expectedInstructions = getPhysicalDatabaseInstructionSample(instruction);
        when(physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabase.getPhysicalDatabaseIdentifier())).thenReturn(expectedInstructions);
        instructionService.findInstructionByPhyDbId(physicalDatabase.getPhysicalDatabaseIdentifier());
        verify(physicalDatabaseInstructionRepository, times(1)).findByPhysicalDatabaseId(physicalDatabase.getPhysicalDatabaseIdentifier());
    }

    @Test
    void checkLogicalDatabaseByAdapterForMigrationTest() {
        List<Database> testDatabases = generateListOfLogicalDatabase(2);
        when(physicalDatabasesService.getDatabasesByPhysDbAndType(TEST_PHYDBID, TEST_TYPE)).thenReturn(testDatabases);
        List<Database> databaseForMigration = instructionService.getLogicalDatabasesForMigration(
                TEST_PHYDBID,
                TEST_TYPE,
                Arrays.asList("admin", "rw", "ro"));
        assertEquals(databaseForMigration.size(), 2);
        databaseForMigration = instructionService.getLogicalDatabasesForMigration(
                TEST_PHYDBID,
                TEST_TYPE,
                Arrays.asList("admin"));
        assertTrue(databaseForMigration.isEmpty());
    }

    @Test
    void saveConnectionPropertiesAfterRolesRegistration() throws JsonProcessingException {
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(databaseDbaasRepository);
        Instruction instruction = generateInstruction(2);
        PhysicalDatabaseInstruction physicalDatabaseInstruction = getPhysicalDatabaseInstructionSample(instruction);
        SuccessRegistrationV3 successRegistrationV3 = new SuccessRegistrationV3();
        String type = "postgresql";
        DatabaseRegistry database = createDatabase(testClassifier(), type, "adapter", "username", "dbName");
        successRegistrationV3.setId(database.getDatabase().getId());
        successRegistrationV3.setConnectionProperties(database.getConnectionProperties());
        successRegistrationV3.setResources(database.getResources());
        when(databaseDbaasRepository.findById(database.getDatabase().getId())).thenReturn(Optional.of(database.getDatabase()));
        instructionService.saveConnectionPropertiesAfterRolesRegistration(List.of(successRegistrationV3));
        verify(dBaaService, times(1)).updateDatabaseConnectionPropertiesAndResourcesById(database.getDatabase().getId(), database.getConnectionProperties(), database.getResources());
    }

    private PhysicalDatabaseRegistryRequestV3 getPhysicalDatabaseRegistryRequestV3Sample() {
        final PhysicalDatabaseRegistryRequestV3 physicalDatabaseRegistryRequestV3 = new PhysicalDatabaseRegistryRequestV3();
        Map<String, Boolean> features = new HashMap<>();
        features.put("multiusers", true);
        physicalDatabaseRegistryRequestV3.setAdapterAddress("test-address");
        physicalDatabaseRegistryRequestV3.setHttpBasicCredentials(new HttpBasicCredentials(TEST_USERNAME, TEST_PASSWORD));
        physicalDatabaseRegistryRequestV3.setAdapterAddress(TEST_ADAPTER_ADDRESS);
        physicalDatabaseRegistryRequestV3.setStatus("running");
        physicalDatabaseRegistryRequestV3.setMetadata(new Metadata("API version",
                Arrays.asList(Role.ADMIN.toString(), "ro"), features));
        return physicalDatabaseRegistryRequestV3;
    }

    private List<Database> generateListOfLogicalDatabase(int count) {
        List<Database> logicalDatabases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Database logicalDatabase = new Database();
            logicalDatabase.setId(UUID.randomUUID());
            Map<String, Object> connectionPropertiesForAdmin = new HashMap<>();
            connectionPropertiesForAdmin.put("id", i);
            connectionPropertiesForAdmin.put("role", "admin");
            Map<String, Object> connectionPropertiesForRo = new HashMap<>();
            connectionPropertiesForRo.put("id", i);
            connectionPropertiesForRo.put("role", "ro");
            logicalDatabase.setConnectionProperties(Arrays.asList(connectionPropertiesForAdmin, connectionPropertiesForRo));
            logicalDatabase.setResources(Collections.singletonList(new DbResource("someKind", "someName")));
            logicalDatabase.setName("test-dbname");
            logicalDatabases.add(logicalDatabase);
        }
        return logicalDatabases;
    }

    private InstructionRequestV3 getSuccessfulInstructionRequestV3Sample() {
        final InstructionRequestV3 instructionRequest = new InstructionRequestV3();
        List<SuccessRegistrationV3> successRegistrationV3List = Collections.singletonList(new SuccessRegistrationV3(PHYDBID, new ArrayList<>(), new ArrayList<>()));
        instructionRequest.setSuccess(successRegistrationV3List);
        return instructionRequest;
    }

    private Instruction generateInstruction(int portionCount) {
        Instruction instruction = new Instruction();
        DbResource dbResource = new DbResource("resourceKind","resourceName");
        dbResource.setId(UUID.fromString("bcde070d-8c4c-4f0d-9d8a-162843c10333"));
        List<DbResource> dbResourceList = new ArrayList<>();
        dbResourceList.add(dbResource);
        instruction.setId(TEST_INSTRUCTION_ID);
        List<AdditionalRoles> additionalRolesList = new ArrayList<>();
        for (int i = 0; i < portionCount * PORTION_SIZE; i++) {
            Map<String, Object> connectionPropertiesForAdmin = new HashMap<>();
            connectionPropertiesForAdmin.put("role", Role.ADMIN.toString());
            AdditionalRoles additionalRoles = new AdditionalRoles(UUID.randomUUID(), "test-dbname",
                    Collections.singletonList(connectionPropertiesForAdmin), dbResourceList);
            additionalRolesList.add(additionalRoles);
        }
        instruction.setAdditionalRoles(additionalRolesList);

        return instruction;
    }

    private HttpBasicCredentials getHttpBasicCredentialsSample() {
        return new HttpBasicCredentials(TEST_USERNAME, TEST_PASSWORD);
    }

    private PhysicalDatabase getPhysicalDatabaseSample() {
        final PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier(TEST_PHYDBIDENTIFIER);
        physicalDatabase.setId(TEST_PHYDBID);
        physicalDatabase.setType(TEST_TYPE);
        physicalDatabase.setLabels(TEST_LABELS);
        physicalDatabase.setRoles(Collections.singletonList(Role.ADMIN.toString()));
        physicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(TEST_ADAPTER_ID, TEST_ADAPTER_ADDRESS, getHttpBasicCredentialsSample(), VERSION_2, null));
        return physicalDatabase;
    }

    private PhysicalDatabaseInstruction getPhysicalDatabaseInstructionSample(Instruction instruction) throws JsonProcessingException {

        PhysicalDatabaseInstruction physicalDatabaseInstruction = new PhysicalDatabaseInstruction();
        physicalDatabaseInstruction.setId(UUID.fromString(TEST_INSTRUCTION_ID));
        physicalDatabaseInstruction.setInstructionType(InstructionType.MULTIUSERS_MIGRATION);
        physicalDatabaseInstruction.setPhysicalDatabaseId(TEST_PHYDBIDENTIFIER);
        physicalDatabaseInstruction.setContext(instructionService.listToString(instruction.getAdditionalRoles()));
        physicalDatabaseInstruction.setPhysicalDbRegRequest(getPhysicalDatabaseRegistryRequestV3Sample());
        Date timeDbInstructionCreation = new Date();
        physicalDatabaseInstruction.setTimeCreation(timeDbInstructionCreation);
        return physicalDatabaseInstruction;
    }

    private DatabaseRegistry createDatabase(Map<String, Object> classifier, String type, String adapterId, String username, String dbName) {
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
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        databaseRegistry.setDatabase(database);
        database.setName(dbName);
        database.setAdapterId(adapterId);
        database.setSettings(new HashMap<String, Object>() {{
            put("setting-one", "value-one");
        }});
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setResources(new LinkedList<>(Arrays.asList(new DbResource("username", username),
                new DbResource("database", dbName))));
        return databaseRegistry;
    }

    private SortedMap<String, Object> testClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test-microservice");
        classifier.put("isServiceDb", true);
        classifier.put("namespace", NAMESPACE);
        return classifier;
    }
}
