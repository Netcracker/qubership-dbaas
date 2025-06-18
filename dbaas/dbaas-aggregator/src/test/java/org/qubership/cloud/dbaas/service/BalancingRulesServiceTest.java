package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qubership.cloud.dbaas.converter.ListRuleOnMicroserviceConverter;
import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.v3.DebugRulesDbTypeData;
import org.qubership.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDTO;
import org.qubership.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDeleteDTO;
import org.qubership.cloud.dbaas.dto.v3.ValidateRulesResponse;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import org.qubership.cloud.dbaas.exceptions.InvalidMicroserviceRuleSizeException;
import org.qubership.cloud.dbaas.exceptions.OnMicroserviceBalancingRuleDuplicateException;
import org.qubership.cloud.dbaas.exceptions.OnMicroserviceBalancingRuleException;
import org.qubership.cloud.dbaas.repositories.dbaas.BalancingRulesDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.*;

import static org.qubership.cloud.dbaas.service.BalancingRulesService.PER_NAMESPACE;
import static org.qubership.cloud.dbaas.service.BalancingRulesService.PHYSICAL_DATABASE_IDENTIFIER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Slf4j
public class BalancingRulesServiceTest {

    @Mock
    private BalancingRulesDbaasRepository balancingRulesDbaasRepository;

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    private DatabaseDbaasRepository databaseDbaasRepository;

    @InjectMocks
    private BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, false);

    private final String TEST_NAMESPACE = "test-namespace";
    private final String TEST_DB_TYPE = "test-db-type";
    private final String TEST_MICROSERVICE = "test-microservice";
    private final String TEST_DB_ID = "test-db-id";

    @Test
    public void testSaveNewRule() {
        final String ruleName = "test-rule";
        final RuleRegistrationRequest ruleRegistrationRequest = getRuleRegistrationRequestSample();
        final PerNamespaceRule perNamespaceRule = getPerNamespaceRuleSample();
        when(balancingRulesDbaasRepository.findByNamespace(TEST_NAMESPACE)).thenReturn(Collections.singletonList(perNamespaceRule));
        boolean actualValue = balancingRulesService.saveOnNamespace(ruleName, TEST_NAMESPACE, ruleRegistrationRequest);
        assertFalse(actualValue);

        verify(balancingRulesDbaasRepository).findByNamespace(TEST_NAMESPACE);
        verify(balancingRulesDbaasRepository).save(any(PerNamespaceRule.class));
        verify(balancingRulesDbaasRepository).findByName(ruleName);
        verifyNoMoreInteractions(balancingRulesDbaasRepository);
    }

    @Test
    public void testSaveUpdateMode() {
        final String ruleName = "test-rule";
        final RuleRegistrationRequest ruleRegistrationRequest = getRuleRegistrationRequestSample();
        final PerNamespaceRule perNamespaceRule = getPerNamespaceRuleSample();
        when(balancingRulesDbaasRepository.findByName(ruleName)).thenReturn(perNamespaceRule);
        boolean actualValue = balancingRulesService.saveOnNamespace(ruleName, TEST_NAMESPACE, ruleRegistrationRequest);
        assertTrue(actualValue);

        verify(balancingRulesDbaasRepository).save(any(PerNamespaceRule.class));
        verify(balancingRulesDbaasRepository).findByName(ruleName);
        verifyNoMoreInteractions(balancingRulesDbaasRepository);
    }

    @Test
    public void testRulesRegistrationOrdering() {
        final String ruleName = "test-rule-2";
        final PerNamespaceRule existingRule = new PerNamespaceRule("test-rule", 1L, TEST_DB_TYPE, TEST_NAMESPACE, "test-phybid", RuleType.NAMESPACE);
        when(balancingRulesDbaasRepository.findByNamespace(TEST_NAMESPACE)).thenReturn(Collections.singletonList(existingRule));

        ArgumentCaptor<PerNamespaceRule> savingRule = ArgumentCaptor.forClass(PerNamespaceRule.class);

        balancingRulesService.saveOnNamespace(ruleName, TEST_NAMESPACE, getRuleRegistrationRequestSample());

        verify(balancingRulesDbaasRepository).save(savingRule.capture());
        assertEquals(Long.valueOf(2L), savingRule.getValue().getOrder());

        verify(balancingRulesDbaasRepository).findByName(ruleName);
        verify(balancingRulesDbaasRepository).findByNamespace(TEST_NAMESPACE);
        verify(balancingRulesDbaasRepository).save(any(PerNamespaceRule.class));
        verifyNoMoreInteractions(balancingRulesDbaasRepository);
    }

    @Test
    public void testRulesApplicationOrder() {
        final String phyDbId1 = "test-phybid-1";
        final String phyDbId2 = "test-phybid-2";
        final PerNamespaceRule perNamespaceRule1 = new PerNamespaceRule(
                "test-rule-1",
                1L,
                TEST_DB_TYPE,
                TEST_NAMESPACE,
                phyDbId1, RuleType.NAMESPACE);
        final PerNamespaceRule perNamespaceRule2 = new PerNamespaceRule(
                "test-rule-2",
                2L,
                TEST_DB_TYPE,
                TEST_NAMESPACE,
                phyDbId2, RuleType.NAMESPACE);
        when(balancingRulesDbaasRepository.findByNamespaceAndRuleType(TEST_NAMESPACE, RuleType.NAMESPACE)).thenReturn(Arrays.asList(perNamespaceRule1, perNamespaceRule2));
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(anyString())).thenAnswer((Answer<PhysicalDatabase>) invocation -> {
            PhysicalDatabase physicalDatabase = new PhysicalDatabase();
            physicalDatabase.setId(invocation.getArgument(0));
            return physicalDatabase;
        });

        PhysicalDatabase physicalDatabase = balancingRulesService.applyNamespaceBalancingRule(TEST_NAMESPACE, TEST_DB_TYPE);

        assertEquals(phyDbId2, physicalDatabase.getId());

        verify(balancingRulesDbaasRepository).findByNamespaceAndRuleType(TEST_NAMESPACE, RuleType.NAMESPACE);
        verify(physicalDatabasesService).getByPhysicalDatabaseIdentifier(phyDbId2);
        verifyNoMoreInteractions(balancingRulesDbaasRepository, physicalDatabasesService);
    }

    @Test
    public void testSaveMicroserviceRule() {
        final List<OnMicroserviceRuleRequest> ruleRegistrationRequest = getRuleRegistrationPerMicroserviceRequestSample();
        final PerMicroserviceRule perMicroserviceRule = getPerMicroserviceRuleSample();

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Collections.singletonList(perMicroserviceRule));

        PhysicalDatabase testPhysicalDatabase = new PhysicalDatabase();
        testPhysicalDatabase.setLabels(new HashMap<String, String>() {{
            put("test", "some");
        }});
        when(balancingRulesDbaasRepository.saveAll(any())).thenReturn(Collections.singletonList(perMicroserviceRule));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(testPhysicalDatabase));

        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(ruleRegistrationRequest, TEST_NAMESPACE);

        assertEquals(1, rules.size());
        verify(balancingRulesDbaasRepository, times(1)).saveAll(any());
        verify(balancingRulesDbaasRepository, times(1)).findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE);
        verifyNoMoreInteractions(balancingRulesDbaasRepository);
    }

    @Test
    public void testUpdateMicroserviceRule() {
        final List<OnMicroserviceRuleRequest> ruleRegistrationRequest = getRuleRegistrationPerMicroserviceRequestSample();
        final PerMicroserviceRule perMicroserviceRule = getPerMicroserviceRuleSample();
        perMicroserviceRule.getRules().get(0).setLabel("test=some2");
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Collections.singletonList(perMicroserviceRule));

        PhysicalDatabase testPhysicalDatabase = new PhysicalDatabase();
        testPhysicalDatabase.setLabels(new HashMap<String, String>() {{
            put("test", "some");
        }});
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(testPhysicalDatabase));
        when(balancingRulesDbaasRepository.saveAll(anyList())).then(returnsFirstArg());
        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(ruleRegistrationRequest, TEST_NAMESPACE);

        assertEquals(1, rules.size());
        verify(balancingRulesDbaasRepository, times(1)).saveAll(any());
        verify(balancingRulesDbaasRepository, times(1)).findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE);
        verifyNoMoreInteractions(balancingRulesDbaasRepository);
    }

    @Test
    public void testAddRuleOnMicroservice() {
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Collections.singletonList(getPerMicroserviceRuleSample()));
        when(balancingRulesDbaasRepository.saveAll(any())).thenReturn(Collections.singletonList(getPerMicroserviceRuleSample()));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(getPhysicalDatabaseSample()));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();

        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE);
        assertEquals(1, rules.size());
    }

    @Test
    void testGetOnMicroserviceBalancingRules() {
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NAMESPACE)).
            thenReturn(Collections.singletonList(getPerMicroserviceRuleSample()));

        var onMicroserviceBalancingRules = balancingRulesService.getOnMicroserviceBalancingRules(TEST_NAMESPACE);

        assertEquals(1, onMicroserviceBalancingRules.size());
        verify(balancingRulesDbaasRepository).findPerMicroserviceByNamespace(TEST_NAMESPACE);
    }

    @Test
    public void testNotFoundLabel() {
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Collections.singletonList(getPerMicroserviceRuleSample()));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(null);

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        Assertions.assertThrows(RuntimeException.class, () -> {
            balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE);
        });
    }

    @Test
    public void testMoreThanOneDatabaseWithLabel() {
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Collections.singletonList(getPerMicroserviceRuleSample()));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Arrays.asList(new PhysicalDatabase(), new PhysicalDatabase()));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        Assertions.assertThrows(RuntimeException.class, () -> {
            balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE);
        });
    }

    @Test
    public void testAddRuleOnMicroserviceNoConflict() {
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(getPhysicalDatabaseSample()));
        when(balancingRulesDbaasRepository.saveAll(anyList())).then(returnsFirstArg());

        Database database = new Database();
        database.setType(TEST_DB_TYPE);

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();

        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE);
        assertEquals(1, rules.size());
    }

    @Test
    public void testAddRuleOnMicroserviceNewGeneration() {
        String TEST_SECOND_MICROSERVICE = TEST_MICROSERVICE + "second";
        String TEST_DB_TYPE_SECOND = TEST_DB_TYPE + "second";
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(getPhysicalDatabaseSample()));
        when(balancingRulesDbaasRepository.saveAll(anyList())).then(returnsFirstArg());
        PerMicroserviceRule existingRule1 = new PerMicroserviceRule(TEST_NAMESPACE, TEST_MICROSERVICE, new ArrayList<>(), TEST_DB_TYPE, new Date(), new Date(), 1);
        PerMicroserviceRule existingRule2 = new PerMicroserviceRule(TEST_NAMESPACE, TEST_SECOND_MICROSERVICE, new ArrayList<>(), TEST_DB_TYPE, new Date(), new Date(), 2);
        PerMicroserviceRule existingRule3 = new PerMicroserviceRule(TEST_NAMESPACE, TEST_SECOND_MICROSERVICE, new ArrayList<>(), TEST_DB_TYPE_SECOND, new Date(), new Date(), 3);
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(eq(TEST_NAMESPACE))).thenReturn(List.of(existingRule1, existingRule2, existingRule3));

        List<OnMicroserviceRuleRequest> request = new ArrayList<>();
        request.addAll(getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, TEST_MICROSERVICE, "test=some"));
        request.addAll(getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, TEST_SECOND_MICROSERVICE, "test=some"));

        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE);
        assertEquals(2, rules.size());
        assertEquals(3, rules.get(0).getGeneration());
        assertEquals(3, rules.get(1).getGeneration());
    }


    @Test
    public void testAddRuleOnMicroserviceNoConflictUpdate() {
        PerMicroserviceRule rule = getPerMicroserviceRuleSample();
        rule.setRules(Collections.singletonList(new RuleOnMicroservice("diff")));

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Collections.singletonList(rule));

        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(getPhysicalDatabaseSample()));
        when(balancingRulesDbaasRepository.saveAll(anyList())).then(returnsFirstArg());

        Database database = new Database();
        database.setType(TEST_DB_TYPE);

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE);
        assertEquals(1, rules.size());
    }

    @Test
    public void testAddRuleOnMicroserviceBadRequest() {
        PerMicroserviceRule rule = getPerMicroserviceRuleSample();
        rule.setRules(Collections.singletonList(new RuleOnMicroservice("diff")));

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).
                thenReturn(Arrays.asList(rule));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        request.get(0).getRules().add(new RuleOnMicroservice("test"));
        assertThrows(InvalidMicroserviceRuleSizeException.class, () ->
                balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE));
    }

    @Test
    void testAddRuleOnMicroserviceDuplicateError() {
        PhysicalDatabase firstDb = getPhysicalDatabase("firstDb", Map.of("test", "first"));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "first", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(firstDb));

        List<String> microservices = Collections.singletonList(TEST_MICROSERVICE);
        List<OnMicroserviceRuleRequest> request = List.of(
                getOnMicroserviceRuleRequest(TEST_DB_TYPE, microservices, "test=first"),
                getOnMicroserviceRuleRequest(TEST_DB_TYPE, microservices, "test=second")
        );

        assertThrows(OnMicroserviceBalancingRuleDuplicateException.class, () -> balancingRulesService.addRuleOnMicroservice(request, TEST_NAMESPACE));
    }

    @Test
    public void testApplyMicroserviceBalancingNullRule() {
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(TEST_NAMESPACE, TEST_MICROSERVICE, TEST_DB_TYPE))
                .thenReturn(Optional.empty());

        assertNull(balancingRulesService.applyMicroserviceBalancingRule(TEST_NAMESPACE, TEST_MICROSERVICE, TEST_DB_TYPE));
    }

    @Test
    public void testSerializeDeserializeRuleOnMicroservice() throws JsonProcessingException {
        PerMicroserviceRule perMicroserviceRule = getPerMicroserviceRuleSample();
        ListRuleOnMicroserviceConverter converter = new ListRuleOnMicroserviceConverter();
        List<RuleOnMicroservice> ruleOnMicroservices = perMicroserviceRule.getRules();
        String ruleAsString = converter.convertToDatabaseColumn(ruleOnMicroservices);
        log.info("Serialized rule: {}", ruleAsString);
        assertNotNull(ruleAsString);

        List<RuleOnMicroservice> ruleFromString = converter.convertToEntityAttribute(ruleAsString);
        assertEquals(ruleOnMicroservices.size(), ruleFromString.size());
        assertEquals(ruleOnMicroservices, ruleFromString);
    }

    @Test
    public void testApplyMicroserviceBalancingRule() {
        PhysicalDatabase expectedDatabase = getPhysicalDatabaseSample();
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(TEST_NAMESPACE, TEST_MICROSERVICE, TEST_DB_TYPE))
                .thenReturn(Optional.of(getPerMicroserviceRuleSample()));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(expectedDatabase));
        PhysicalDatabase database = balancingRulesService.applyMicroserviceBalancingRule(TEST_NAMESPACE, TEST_MICROSERVICE, TEST_DB_TYPE);
        assertEquals(expectedDatabase, database);
    }

    @Test
    void testValidationMicroserviceRulesIsSuccessful() throws InvalidMicroserviceRuleSizeException {
        PhysicalDatabase expectedDatabase = getPhysicalDatabaseSample();
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(expectedDatabase));

        String testLabel = "test=some";
        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        Map<String, String> mapLabelToDb = balancingRulesService.mapLabelsToPhysicalDatabases(request, TEST_NAMESPACE);
        assertEquals(1, mapLabelToDb.size());
        assertNotNull(mapLabelToDb.get(testLabel));
        String physDb = mapLabelToDb.get(testLabel);
        assertEquals(TEST_DB_ID, physDb);
    }

    @Test
    void testValidationMicroserviceRulesFailsWithDuplicateRules() {
        PhysicalDatabase firstDb = getPhysicalDatabase("firstDb", Map.of("test", "first"));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "first", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(firstDb));

        List<String> microservices = Collections.singletonList(TEST_MICROSERVICE);
        List<OnMicroserviceRuleRequest> request = List.of(
                getOnMicroserviceRuleRequest(TEST_DB_TYPE, microservices, "test=first"),
                getOnMicroserviceRuleRequest(TEST_DB_TYPE, microservices, "test=second")
        );

        assertThrows(OnMicroserviceBalancingRuleDuplicateException.class, () -> balancingRulesService.mapLabelsToPhysicalDatabases(request, TEST_NAMESPACE));
    }

    @Test
    void testValidationMicroserviceRulesStatusCodeIsOk() {
        PhysicalDatabase expectedDatabase = getPhysicalDatabaseSample();
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(expectedDatabase));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        ValidateRulesResponse response = balancingRulesService.validateMicroservicesRules(request, TEST_NAMESPACE);
        assertNotNull(response);
    }

    @Test
    void testCollectDefaultDatabases() {
        String pgType = "postgresql";
        PhysicalDatabase pgDbGlobal = new PhysicalDatabase();
        pgDbGlobal.setPhysicalDatabaseIdentifier("global-pg");
        pgDbGlobal.setType(pgType);
        pgDbGlobal.setGlobal(true);
        PhysicalDatabase pgDb = new PhysicalDatabase();
        pgDb.setType(pgType);
        pgDb.setPhysicalDatabaseIdentifier("pg");
        pgDb.setGlobal(false);

        String mgType = "mongodb";
        PhysicalDatabase mgDbGlobal = new PhysicalDatabase();
        mgDbGlobal.setType(mgType);
        mgDbGlobal.setGlobal(true);
        mgDbGlobal.setPhysicalDatabaseIdentifier("global-mg");
        when(physicalDatabasesService.getAllRegisteredDatabases()).thenReturn(List.of(pgDbGlobal, pgDb, mgDbGlobal));

        Map<String, String> defaultDbs = balancingRulesService.collectDefaultDatabases();
        assertEquals(2, defaultDbs.size());
        assertNotNull(defaultDbs.get(pgType));
        assertEquals("global-pg", defaultDbs.get(pgType));
        assertNotNull(defaultDbs.get(mgType));
        assertEquals("global-mg", defaultDbs.get(mgType));
    }

    @Test
    void testMoreThanOneRuleInRequest() {
        OnMicroserviceRuleRequest ruleRegistrationRequest = new OnMicroserviceRuleRequest();
        ruleRegistrationRequest.setType(TEST_DB_TYPE);
        ruleRegistrationRequest.setMicroservices(Collections.singletonList(TEST_MICROSERVICE));
        RuleOnMicroservice firstRule = new RuleOnMicroservice();
        firstRule.setLabel("test_one=first");
        RuleOnMicroservice secondRule = new RuleOnMicroservice();
        secondRule.setLabel("test_two=second");
        ruleRegistrationRequest.setRules(List.of(firstRule, secondRule));

        List<OnMicroserviceRuleRequest> request = Collections.singletonList(ruleRegistrationRequest);
        InvalidMicroserviceRuleSizeException e = assertThrows(InvalidMicroserviceRuleSizeException.class, () ->
                balancingRulesService.validateMicroservicesRules(request, TEST_NAMESPACE));
        assertTrue(e.getMessage().contains("Rules can contain only one label"));
    }

    @Test
    void testNoRulesInRequest() {
        OnMicroserviceRuleRequest ruleRegistrationRequest = new OnMicroserviceRuleRequest();
        ruleRegistrationRequest.setType(TEST_DB_TYPE);
        ruleRegistrationRequest.setMicroservices(Collections.singletonList(TEST_MICROSERVICE));
        ruleRegistrationRequest.setRules(Collections.emptyList());

        List<OnMicroserviceRuleRequest> request = Collections.singletonList(ruleRegistrationRequest);
        InvalidMicroserviceRuleSizeException e = assertThrows(InvalidMicroserviceRuleSizeException.class, () ->
                balancingRulesService.validateMicroservicesRules(request, TEST_NAMESPACE));
        assertTrue(e.getMessage().contains("Rules should contain any label"));
    }

    @Test
    void testLabelDoesNotExist() {
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.emptyList());

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequestSample();
        OnMicroserviceBalancingRuleException e = assertThrows(OnMicroserviceBalancingRuleException.class, () ->
                balancingRulesService.validateMicroservicesRules(request, TEST_NAMESPACE));
        assertTrue(e.getMessage().contains("Physical database with label: test=some not registered "));
    }

    @Test
    void testSuccessfullyGetCheckPhysicalDatabaseByLabel() {
        PhysicalDatabase expectedDatabase = getPhysicalDatabaseSample();
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(expectedDatabase));

        PhysicalDatabase testDb = balancingRulesService.getCheckPhysicalDatabaseByLabel("test=some", TEST_DB_TYPE);
        assertEquals(expectedDatabase, testDb);
    }

    @Test
    void testGetCheckPhysicalDatabaseByLabelWithBadFormatLabel() {
        String badFormatLabel = "test:some";
        Exception exception = assertThrows(RuntimeException.class, () -> {
            balancingRulesService.getCheckPhysicalDatabaseByLabel(badFormatLabel, TEST_DB_TYPE);
        });
        assertTrue(exception.getMessage().contains("Labels=" + badFormatLabel + " from request incorrect"));
    }

    @Test
    void testGetCheckPhysicalDatabaseByLabelNoLabelOnDatabase() {
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(Collections.emptyList());

        String requestLabels = "test=some";
        Exception exception = assertThrows(RuntimeException.class, () -> {
            balancingRulesService.getCheckPhysicalDatabaseByLabel(requestLabels, TEST_DB_TYPE);
        });
        assertTrue(exception.getMessage().contains("Physical database with label: " + requestLabels + " not registered "));
    }

    @Test
    void testGetCheckPhysicalDatabaseByLabelLabelMoreThanOnOneDb() {
        PhysicalDatabase firstDb = getPhysicalDatabaseSample();
        PhysicalDatabase secondDB = getPhysicalDatabaseSample();
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("test", "some", TEST_DB_TYPE)).
                thenReturn(List.of(firstDb, secondDB));

        String requestLabels = "test=some";
        Exception exception = assertThrows(RuntimeException.class, () -> {
            balancingRulesService.getCheckPhysicalDatabaseByLabel(requestLabels, TEST_DB_TYPE);
        });
        assertTrue(exception.getMessage().contains("More than one physical database registered with label: " + requestLabels));
    }

    @Test
    void testGetPermanentOnNamespaceRule() {
        PerNamespaceRule firstRule = getPerNamespaceRuleSample();
        PerNamespaceRule secondRule = getPerNamespaceRuleSample();
        secondRule.setNamespace("another-namespace");
        when(balancingRulesDbaasRepository.findByRuleType(RuleType.PERMANENT)).thenReturn(List.of(firstRule, secondRule));
        List<PermanentPerNamespaceRuleDTO> response = balancingRulesService.getPermanentOnNamespaceRule();
        assertEquals(1, response.size());
        assertEquals(firstRule.getPhysicalDatabaseIdentifier(), response.get(0).getPhysicalDatabaseId());
        assertEquals(2, response.get(0).getNamespaces().size());
    }

    @Test
    void testGetPermanentOnNamespaceRuleForNamespace() {
        PerNamespaceRule rule = getPerNamespaceRuleSample();
        when(balancingRulesDbaasRepository.findByNamespaceAndRuleType(TEST_NAMESPACE, RuleType.PERMANENT)).thenReturn(List.of(rule));
        List<PermanentPerNamespaceRuleDTO> response = balancingRulesService.getPermanentOnNamespaceRule(TEST_NAMESPACE);
        assertEquals(1, response.size());
        assertEquals(rule.getPhysicalDatabaseIdentifier(), response.get(0).getPhysicalDatabaseId());
        assertEquals(1, response.get(0).getNamespaces().size());
    }

    @Test
    void testDeletePermanentRulesWithDbType() {
        List<PermanentPerNamespaceRuleDeleteDTO> rulesToDelete = new ArrayList<>();
        rulesToDelete.add(new PermanentPerNamespaceRuleDeleteDTO(TEST_DB_TYPE, Set.of(TEST_NAMESPACE, TEST_NAMESPACE + "1", TEST_NAMESPACE + "2")));
        balancingRulesService.deletePermanentRules(rulesToDelete);
        verify(balancingRulesDbaasRepository, times(3)).deleteByNamespaceAndDbTypeAndRuleType(anyString(), anyString(), any(RuleType.class));
    }

    @Test
    void testDeletePermanentRulesWithoutDbType() {
        List<PermanentPerNamespaceRuleDeleteDTO> rulesToDelete = new ArrayList<>();
        rulesToDelete.add(new PermanentPerNamespaceRuleDeleteDTO("", Set.of(TEST_NAMESPACE, TEST_NAMESPACE + "1", TEST_NAMESPACE + "2")));
        balancingRulesService.deletePermanentRules(rulesToDelete);
        verify(balancingRulesDbaasRepository, times(3)).deleteByNamespaceAndRuleType(anyString(), any(RuleType.class));
    }

    @Test
    void testCopyNamespaceRule() {
        PerNamespaceRule firstRule = getPerNamespaceRuleSample();
        when(balancingRulesDbaasRepository.findAllRulesByNamespace(TEST_NAMESPACE)).thenReturn(Arrays.asList(firstRule));
        balancingRulesService.copyNamespaceRule(TEST_NAMESPACE, "target-namespace");
        verify(balancingRulesDbaasRepository, times(1))
                .saveAllNamespaceRules(argThat((List<PerNamespaceRule> savedRules) ->
                        savedRules.get(0).getNamespace().equals("target-namespace")
                                && !savedRules.get(0).getName().equals(firstRule.getName()))
                );
    }

    @Test
    void testCopyMicroserviceRule() {
        PerMicroserviceRule firstRule = getPerMicroserviceRuleSample();
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(TEST_NAMESPACE)).thenReturn(Arrays.asList(firstRule));
        balancingRulesService.copyMicroserviceRule(TEST_NAMESPACE, "target-namespace");
        verify(balancingRulesDbaasRepository, times(1))
                .saveAll(argThat((List<PerMicroserviceRule> savedRules) ->
                        savedRules.get(0).getNamespace().equals("target-namespace"))
                );
    }

    @Test
    void testReturnDefaultPhysicalDatabase() {
        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, false);
        when(balancingRulesDbaasRepository.findByNamespaceAndRuleType(TEST_NAMESPACE, RuleType.NAMESPACE)).thenReturn(List.of());
        PhysicalDatabase sampleDatabase = getPhysicalDatabaseSample();
        when(physicalDatabasesService.balanceByType(TEST_DB_TYPE)).thenReturn(sampleDatabase);
        PhysicalDatabase physicalDatabase = balancingRulesService.applyNamespaceBalancingRule(TEST_NAMESPACE, TEST_DB_TYPE);
        verify(physicalDatabasesService, times(1)).balanceByType(TEST_DB_TYPE);
        assertEquals(sampleDatabase, physicalDatabase);
    }

    @Test
    void testForbidReturnOfDefaultPhysicalDatabase() {
        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, true);
        when(balancingRulesDbaasRepository.findByNamespaceAndRuleType(TEST_NAMESPACE, RuleType.NAMESPACE)).thenReturn(List.of());
        PhysicalDatabase physicalDatabase = balancingRulesService.applyNamespaceBalancingRule(TEST_NAMESPACE, TEST_DB_TYPE);
        verify(physicalDatabasesService, times(0)).balanceByType(TEST_DB_TYPE);
        assertNull(physicalDatabase);
    }

    @Test
    void testDebugBalancingRulesNewRuleApplied() {
        PhysicalDatabase newDatabase = getPhysicalDatabase("new", Map.of("db", "new"));

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(TEST_NAMESPACE, TEST_MICROSERVICE, TEST_DB_TYPE))
                .thenReturn(Optional.of(getPerMicroserviceRule(TEST_NAMESPACE, TEST_MICROSERVICE, "db=old", TEST_DB_TYPE)));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("db", "new", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(newDatabase));
        when(physicalDatabasesService.getAllRegisteredDatabases()).
                thenReturn(Collections.singletonList(newDatabase));

        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, true);

        Map<String, String> testLabel = Map.of("db", "new");
        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, TEST_MICROSERVICE, "db=new");
        List<String> microservices = List.of(TEST_MICROSERVICE);

        Map<String, Map<String, DebugRulesDbTypeData>> response = balancingRulesService.debugBalancingRules(request, TEST_NAMESPACE, microservices);
        assertNotNull(response);
        Map<String, DebugRulesDbTypeData> microserviceDebugData = response.get(TEST_MICROSERVICE);
        assertNotNull(microserviceDebugData);
        DebugRulesDbTypeData dbTypeData = microserviceDebugData.get(TEST_DB_TYPE);
        assertNotNull(dbTypeData);
        assertEquals(testLabel, dbTypeData.getLabels());
        assertEquals(newDatabase.getPhysicalDatabaseIdentifier(), dbTypeData.getPhysicalDbIdentifier());
        verify(physicalDatabasesService, times(0)).getPhysicalDatabaseContainsLabel("db", "old", TEST_DB_TYPE);
        assertEquals(DebugRulesDbTypeData.MICROSERVICE_RULE_INFO, dbTypeData.getAppliedRuleInfo());
    }

    @Test
    void testDebugBalancingRulesOldRuleApplied() {
        PhysicalDatabase newDatabase = getPhysicalDatabase("new", Map.of("db", "new"));
        PhysicalDatabase oldDatabase = getPhysicalDatabase("new", Map.of("db", "old"));

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(TEST_NAMESPACE, TEST_MICROSERVICE, TEST_DB_TYPE))
                .thenReturn(Optional.of(getPerMicroserviceRule(TEST_NAMESPACE, TEST_MICROSERVICE, "db=old", TEST_DB_TYPE)));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("db", "new", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(newDatabase));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("db", "old", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(oldDatabase));
        when(physicalDatabasesService.getAllRegisteredDatabases()).
                thenReturn(List.of(newDatabase, oldDatabase));

        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, true);

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, "another" + TEST_MICROSERVICE, "db=new");
        List<String> microservices = List.of(TEST_MICROSERVICE);

        Map<String, Map<String, DebugRulesDbTypeData>> response = balancingRulesService.debugBalancingRules(request, TEST_NAMESPACE, microservices);
        assertNotNull(response);
        Map<String, DebugRulesDbTypeData> microserviceDebugData = response.get(TEST_MICROSERVICE);
        assertNotNull(microserviceDebugData);
        DebugRulesDbTypeData dbTypeData = microserviceDebugData.get(TEST_DB_TYPE);
        assertNotNull(dbTypeData);
        Map<String, String> expectedLabel = Map.of("db", "old");
        assertEquals(expectedLabel, dbTypeData.getLabels());
        assertEquals(oldDatabase.getPhysicalDatabaseIdentifier(), dbTypeData.getPhysicalDbIdentifier());
        assertEquals(DebugRulesDbTypeData.MICROSERVICE_RULE_INFO, dbTypeData.getAppliedRuleInfo());
    }

    @Test
    void testDebugBalancingRulesNamespaceRuleApplied() {
        String microserviceDbId = "microserviceDb";
        PhysicalDatabase microserviceDatabase = getPhysicalDatabase(microserviceDbId, Map.of("db", "microservice"));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("db", "microservice", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(microserviceDatabase));
        String namespaceDbId = "namespaceDb";
        PhysicalDatabase namespaceDatabase = getPhysicalDatabase(namespaceDbId, Map.of("db", "namespace"));
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(namespaceDbId)).
                thenReturn(namespaceDatabase);
        when(physicalDatabasesService.getAllRegisteredDatabases()).
                thenReturn(List.of(microserviceDatabase, namespaceDatabase));

        PerNamespaceRule perNamespaceRule = new PerNamespaceRule(
                "test-rule-1",
                1L,
                TEST_DB_TYPE,
                TEST_NAMESPACE,
                namespaceDbId, RuleType.NAMESPACE);
        when(balancingRulesDbaasRepository.findByNamespaceAndRuleType(TEST_NAMESPACE, RuleType.NAMESPACE)).thenReturn(List.of(perNamespaceRule));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, "another" + TEST_MICROSERVICE, "db=microservice");
        List<String> microservices = List.of(TEST_MICROSERVICE);

        Map<String, Map<String, DebugRulesDbTypeData>> response = balancingRulesService.debugBalancingRules(request, TEST_NAMESPACE, microservices);
        assertNotNull(response);
        Map<String, DebugRulesDbTypeData> microserviceDebugData = response.get(TEST_MICROSERVICE);
        assertNotNull(microserviceDebugData);
        DebugRulesDbTypeData dbTypeData = microserviceDebugData.get(TEST_DB_TYPE);
        assertNotNull(dbTypeData);
        Map<String, String> expectedLabel = Map.of("db", "namespace");
        assertEquals(expectedLabel, dbTypeData.getLabels());
        assertEquals(namespaceDatabase.getPhysicalDatabaseIdentifier(), dbTypeData.getPhysicalDbIdentifier());
        assertEquals(DebugRulesDbTypeData.NAMESPACE_RULE_INFO, dbTypeData.getAppliedRuleInfo());
    }

    @Test
    void testDebugBalancingRulesDefaultRuleApplied() {
        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, false);
        String microserviceDbId = "microserviceDb";
        PhysicalDatabase microserviceDatabase = getPhysicalDatabase(microserviceDbId, Map.of("db", "microservice"));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("db", "microservice", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(microserviceDatabase));
        String defaultDbId = "defaultDb";
        PhysicalDatabase defaultDatabase = getPhysicalDatabase(defaultDbId, Map.of("db", "default"));
        when(physicalDatabasesService.balanceByType(TEST_DB_TYPE)).
                thenReturn(defaultDatabase);
        when(physicalDatabasesService.getAllRegisteredDatabases()).
                thenReturn(Collections.singletonList(defaultDatabase));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, "another" + TEST_MICROSERVICE, "db=microservice");
        List<String> microservices = List.of(TEST_MICROSERVICE);

        Map<String, Map<String, DebugRulesDbTypeData>> response = balancingRulesService.debugBalancingRules(request, TEST_NAMESPACE, microservices);
        assertNotNull(response);
        Map<String, DebugRulesDbTypeData> microserviceDebugData = response.get(TEST_MICROSERVICE);
        assertNotNull(microserviceDebugData);
        DebugRulesDbTypeData dbTypeData = microserviceDebugData.get(TEST_DB_TYPE);
        assertNotNull(dbTypeData);
        Map<String, String> expectedLabel = Map.of("db", "default");
        assertEquals(expectedLabel, dbTypeData.getLabels());
        assertEquals(defaultDatabase.getPhysicalDatabaseIdentifier(), dbTypeData.getPhysicalDbIdentifier());
        assertEquals(DebugRulesDbTypeData.DEFAULT_DATABASE_RULE_INFO, dbTypeData.getAppliedRuleInfo());
    }

    @Test
    void testDebugBalancingRulesNoApplicableRules() {
        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, true);
        String microserviceDbId = "microserviceDb";
        PhysicalDatabase microserviceDatabase = getPhysicalDatabase(microserviceDbId, Map.of("db", "microservice"));
        when(physicalDatabasesService.getPhysicalDatabaseContainsLabel("db", "microservice", TEST_DB_TYPE)).
                thenReturn(Collections.singletonList(microserviceDatabase));
        when(physicalDatabasesService.getAllRegisteredDatabases()).
                thenReturn(Collections.singletonList(microserviceDatabase));

        List<OnMicroserviceRuleRequest> request = getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, "another" + TEST_MICROSERVICE, "db=microservice");
        List<String> microservices = List.of(TEST_MICROSERVICE);

        Map<String, Map<String, DebugRulesDbTypeData>> response = balancingRulesService.debugBalancingRules(request, TEST_NAMESPACE, microservices);
        verify(physicalDatabasesService, times(0)).balanceByType(TEST_DB_TYPE);
        assertNotNull(response);
        Map<String, DebugRulesDbTypeData> microserviceDebugData = response.get(TEST_MICROSERVICE);
        assertNotNull(microserviceDebugData);
        DebugRulesDbTypeData dbTypeData = microserviceDebugData.get(TEST_DB_TYPE);
        assertNotNull(dbTypeData);
        assertNull(dbTypeData.getLabels());
        assertNull(dbTypeData.getPhysicalDbIdentifier());
        assertEquals(DebugRulesDbTypeData.NO_SUITABLE_DATABASE_RULE_INFO, dbTypeData.getAppliedRuleInfo());
    }


    private PerNamespaceRule getPerNamespaceRuleSample() {
        return new PerNamespaceRule("test-rule", 1L, TEST_DB_TYPE, TEST_NAMESPACE, "test-phybid", RuleType.NAMESPACE);
    }


    private RuleRegistrationRequest getRuleRegistrationRequestSample() {
        final RuleRegistrationRequest ruleRegistrationRequest = new RuleRegistrationRequest();
        ruleRegistrationRequest.setType(TEST_DB_TYPE);
        final RuleBody ruleBody = new RuleBody();
        Map<String, Object> config = new HashMap<>();
        Map<String, String> properties = new HashMap<>();
        properties.put(PHYSICAL_DATABASE_IDENTIFIER, "test-id");
        config.put(PER_NAMESPACE, properties);
        ruleBody.setConfig(config);
        ruleRegistrationRequest.setRule(ruleBody);
        return ruleRegistrationRequest;
    }

    private List<OnMicroserviceRuleRequest> getRuleRegistrationPerMicroserviceRequestSample() {
        return getRuleRegistrationPerMicroserviceRequest(TEST_DB_TYPE, TEST_MICROSERVICE, "test=some");
    }

    private List<OnMicroserviceRuleRequest> getRuleRegistrationPerMicroserviceRequest(String dbType, String microservice, String label) {
        return Collections.singletonList(getOnMicroserviceRuleRequest(dbType, Collections.singletonList(microservice), label));
    }

    private OnMicroserviceRuleRequest getOnMicroserviceRuleRequest(String dbType, List<String> microservice, String label) {
        final OnMicroserviceRuleRequest onMicroserviceRuleRequest = new OnMicroserviceRuleRequest();
        onMicroserviceRuleRequest.setType(dbType);
        onMicroserviceRuleRequest.setMicroservices(microservice);
        final RuleOnMicroservice ruleOnMicroservice = new RuleOnMicroservice();
        ruleOnMicroservice.setLabel(label);
        onMicroserviceRuleRequest.setRules(new ArrayList<>(Collections.singletonList(ruleOnMicroservice)));
        return onMicroserviceRuleRequest;
    }

    private PerMicroserviceRule getPerMicroserviceRuleSample() {
        return getPerMicroserviceRule(TEST_NAMESPACE, TEST_MICROSERVICE, "test=some", TEST_DB_TYPE);
    }

    private PerMicroserviceRule getPerMicroserviceRule(String namespace, String microservice, String label, String dbType) {
        return new PerMicroserviceRule(namespace, microservice,
                Collections.singletonList(new RuleOnMicroservice(label)),
                dbType, new Date(), new Date(), 0);
    }

    private PhysicalDatabase getPhysicalDatabaseSample() {
        return getPhysicalDatabase(TEST_DB_ID, Map.of("test", "some"));
    }

    private PhysicalDatabase getPhysicalDatabase(String physicalDatabaseId, Map<String, String> labels) {
        PhysicalDatabase testPhysicalDatabase = new PhysicalDatabase();
        testPhysicalDatabase.setType(TEST_DB_TYPE);
        testPhysicalDatabase.setPhysicalDatabaseIdentifier(physicalDatabaseId);
        testPhysicalDatabase.setLabels(labels);
        return testPhysicalDatabase;
    }

    @Test
    void testAreRulesExistingInNamespace_true() {
        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, true);
        List<PerMicroserviceRule> perMicroserviceRules = List.of(new PerMicroserviceRule());
        List<PerNamespaceRule> perNamespaceRules = List.of(new PerNamespaceRule());

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NAMESPACE)).thenReturn(perMicroserviceRules);
        when(balancingRulesDbaasRepository.findByNamespace(TEST_NAMESPACE)).thenReturn(perNamespaceRules);
        assertTrue(balancingRulesService.areRulesExistingInNamespace(TEST_NAMESPACE));

        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        assertTrue(balancingRulesService.areRulesExistingInNamespace(TEST_NAMESPACE));
    }

    @Test
    void testAreRulesExistingInNamespace_false() {
        BalancingRulesService balancingRulesService = new BalancingRulesService(balancingRulesDbaasRepository, physicalDatabasesService, databaseDbaasRepository, true);
        when(balancingRulesDbaasRepository.findPerMicroserviceByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        when(balancingRulesDbaasRepository.findByNamespace(TEST_NAMESPACE)).thenReturn(Collections.emptyList());
        assertFalse(balancingRulesService.areRulesExistingInNamespace(TEST_NAMESPACE));
    }
}
