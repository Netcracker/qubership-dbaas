package com.netcracker.cloud.dbaas.service;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.dto.OnMicroserviceRuleRequest;
import com.netcracker.cloud.dbaas.dto.RuleOnMicroservice;
import com.netcracker.cloud.dbaas.dto.RuleRegistrationRequest;
import com.netcracker.cloud.dbaas.dto.v3.DebugRulesDbTypeData;
import com.netcracker.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDTO;
import com.netcracker.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDeleteDTO;
import com.netcracker.cloud.dbaas.dto.v3.ValidateRulesResponse;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.dto.RuleType;
import com.netcracker.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import com.netcracker.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import com.netcracker.cloud.dbaas.exceptions.*;
import com.netcracker.cloud.dbaas.repositories.dbaas.BalancingRulesDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BalancingRulesService {
    static final String PHYSICAL_DATABASE_IDENTIFIER = "phydbid";
    static final String PER_NAMESPACE = "perNamespace";
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(BalancingRulesService.class);

    private BalancingRulesDbaasRepository balancingRulesDbaasRepository;

    private PhysicalDatabasesService physicalDatabasesService;

    private DatabaseDbaasRepository databaseDbaasRepository;

    private boolean defaultPhysicalDatabasesDisabled;

    public BalancingRulesService(BalancingRulesDbaasRepository balancingRulesDbaasRepository, PhysicalDatabasesService physicalDatabasesService, DatabaseDbaasRepository databaseDbaasRepository, boolean defaultPhysicalDatabasesDisabled) {
        this.balancingRulesDbaasRepository = balancingRulesDbaasRepository;
        this.physicalDatabasesService = physicalDatabasesService;
        this.databaseDbaasRepository = databaseDbaasRepository;
        this.defaultPhysicalDatabasesDisabled = defaultPhysicalDatabasesDisabled;
    }

    // returns false if new rule was registered, true if update
    @Transactional
    public boolean saveOnNamespace(String ruleName, String namespace, RuleRegistrationRequest ruleRequest) {
        Long requestOrder = ruleRequest.getOrder();
        PerNamespaceRule conflictingRule = requestOrder != null ?
                balancingRulesDbaasRepository.findByOrderAndDatabaseType(requestOrder, ruleRequest.getType()) : null;
        if (conflictingRule != null && !ruleName.equalsIgnoreCase(conflictingRule.getName())) {
            throw new BalancingRuleConflictException(ruleRequest.getType(), requestOrder, conflictingRule.getName());
        }
        String physicalDatabaseIdentifier = getPhysicalDatabaseIdentifier(ruleRequest);
        PerNamespaceRule storedRule = balancingRulesDbaasRepository.findByName(ruleName);
        if (storedRule != null) {
StructuredLog.info(log, "Updating rule", "ruleName", ruleName);
            if (requestOrder != null) {
                storedRule.setOrder(requestOrder);
            }
            storedRule.setPhysicalDatabaseIdentifier(physicalDatabaseIdentifier);
StructuredLog.info(log, "Rule was updated:", "storedRule", storedRule);
            balancingRulesDbaasRepository.save(storedRule);
            return true;
        }
        log.info("New rule creation");
        Long order = requestOrder != null ? requestOrder : computeOrder(namespace, ruleRequest.getType());
StructuredLog.info(log, "Order of new rule:", "order", order);
        PerNamespaceRule newRule = new PerNamespaceRule(ruleName, order, ruleRequest.getType(),
                namespace, physicalDatabaseIdentifier, RuleType.NAMESPACE);
StructuredLog.info(log, "Created rule:", "newRule", newRule);
        PerNamespaceRule savedRule = balancingRulesDbaasRepository.save(newRule);
StructuredLog.info(log, "Rule was saved:", "savedRule", savedRule);
        return false;
    }

    @Transactional
    public List<PermanentPerNamespaceRuleDTO> savePermanentOnNamespace(List<PermanentPerNamespaceRuleDTO> ruleRequest) {
        List<PermanentPerNamespaceRuleDTO> result = new ArrayList<>();
        for (PermanentPerNamespaceRuleDTO rule : ruleRequest) {
            for (String namespace : rule.getNamespaces()) {
                PerNamespaceRule ruleToRegister = balancingRulesDbaasRepository.findPerNamespaceRuleByNamespaceAndDatabaseTypeAndRuleType(namespace, rule.getDbType(), RuleType.PERMANENT);
                if (ruleToRegister != null && isRuleAlreadyRegistered(ruleToRegister, namespace, rule)) {
StructuredLog.info(log, "Rule = is already registered", "ruleToRegister", ruleToRegister);
                    continue;
                }
                if (ruleToRegister == null) {
                    long ruleOrder = 0L;
                    ruleToRegister = new PerNamespaceRule(UUID.randomUUID().toString(), ruleOrder, rule.getDbType(),
                            namespace, rule.getPhysicalDatabaseId(), RuleType.PERMANENT);
StructuredLog.info(log, "New rule was added:", "ruleToRegister", ruleToRegister);
                } else {
                    ruleToRegister.setNamespace(namespace);
                    ruleToRegister.setPhysicalDatabaseIdentifier(rule.getPhysicalDatabaseId());
StructuredLog.info(log, "Rule was updated:", "ruleToRegister", ruleToRegister);
                }

                balancingRulesDbaasRepository.save(ruleToRegister);
            }
            PermanentPerNamespaceRuleDTO responseRule = new PermanentPerNamespaceRuleDTO(rule.getDbType(), rule.getPhysicalDatabaseId(), rule.getNamespaces());
            result.add(responseRule);
        }
        return result;
    }

    private boolean isRuleAlreadyRegistered(PerNamespaceRule ruleWithMaxOrder, String namespace, PermanentPerNamespaceRuleDTO rule) {
        return ruleWithMaxOrder.getDatabaseType().equals(rule.getDbType())
                && ruleWithMaxOrder.getNamespace().equals(namespace)
                && ruleWithMaxOrder.getPhysicalDatabaseIdentifier().equals(rule.getPhysicalDatabaseId());
    }

    @Transactional
    public List<PerMicroserviceRule> addRuleOnMicroservice(List<OnMicroserviceRuleRequest> onMicroserviceRulesRequest, String namespace) {
        Map<String, List<PerMicroserviceRule>> typeToPerMicroserviceRule = getSavedRulesPerDbMicroservice(namespace);

        List<PerMicroserviceRule> rulesToSave = convertToEntity(onMicroserviceRulesRequest, namespace, typeToPerMicroserviceRule);
        rulesToSave = balancingRulesDbaasRepository.saveAll(rulesToSave);
        return rulesToSave;
    }

    public List<PerMicroserviceRule> getOnMicroserviceBalancingRules(String namespace) {
StructuredLog.debug(log, "Start to get on microservice physical database balancing rules for namespace:", "namespace", namespace);

        var onMicroserviceBalancingRules = balancingRulesDbaasRepository.findPerMicroserviceByNamespace(namespace);

        StructuredLog.debug(log, "Finish to get microservice physical database balancing rules for namespace",
                "count", onMicroserviceBalancingRules.size(), "namespace", namespace);
        return onMicroserviceBalancingRules;
    }

    private List<PerMicroserviceRule> convertToEntity(List<OnMicroserviceRuleRequest> newRules,
                                                      String namespace, Map<String, List<PerMicroserviceRule>> existingRulesPerTypeMap) {
        List<PerMicroserviceRule> result = new ArrayList<>();
        int maxGeneration = -1;
        Set<PerMicroserviceRuleKey> processedRules = new HashSet<>();
        for (OnMicroserviceRuleRequest request : newRules) {
            // validate requested rule
            validateForDuplicates(processedRules, request, namespace);
            if (request.getRules().size() > 1) {
                throw new InvalidMicroserviceRuleSizeException("Rules can contains only one label");
            }
            if (request.getRules() != null) {
                // the method below can throw an exception
                checkPhysicalDatabaseByRequestRule(request);
            }

            List<PerMicroserviceRule> existedRules = existingRulesPerTypeMap.get(request.getType());
StructuredLog.debug(log, "start check on", "request", request);
            for (String microservice : request.getMicroservices()) {
                Optional<PerMicroserviceRule> specificRuleToMicroservice = Optional.empty();
                if (existedRules != null) {
                    specificRuleToMicroservice = existedRules.stream().filter(existedRule -> existedRule.getMicroservice().equals(microservice))
                            .max(Comparator.comparing(PerMicroserviceRule::getGeneration));
                }
                PerMicroserviceRule perMicroserviceRule = new PerMicroserviceRule(request.getRules(), request.getType(), namespace, microservice);
                if (specificRuleToMicroservice.isPresent()) {
                    PerMicroserviceRule microserviceRule = specificRuleToMicroservice.get();
                    perMicroserviceRule.setCreateDate(microserviceRule.getCreateDate());
                    maxGeneration = microserviceRule.getGeneration() > maxGeneration ? microserviceRule.getGeneration() : maxGeneration;

                }
                result.add(perMicroserviceRule);
            }
        }

        int nextGeneration = maxGeneration + 1;
        result.forEach(perMicroserviceRule -> perMicroserviceRule.setGeneration(nextGeneration));
        return result;
    }

    public PhysicalDatabase applyMicroserviceBalancingRule(String namespace, String microservice, String databaseType) {
        if (namespace == null || microservice == null || databaseType == null) {
            return null;
        }
        Optional<PerMicroserviceRule> rule = balancingRulesDbaasRepository.findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(namespace, microservice, databaseType);
        return processMicroserviceBalancingRules(namespace, microservice, databaseType, rule.map(List::of).orElseGet(List::of));
    }

    private PhysicalDatabase processMicroserviceBalancingRules(String namespace, String microservice, String databaseType, List<PerMicroserviceRule> rules) {
        Optional<PerMicroserviceRule> ruleToApply =
                rules.stream().max(Comparator.comparingLong(PerMicroserviceRule::getGeneration));
        if (ruleToApply.isEmpty()) {
StructuredLog.warn(log, "Rule in namespace= with microservice= and databaseType= not found", "namespace", namespace, "microservice", microservice, "databaseType", databaseType);
            return null;
        }
        if (ruleToApply.get().getRules() == null || ruleToApply.get().getRules().isEmpty()) {
StructuredLog.warn(log, "Rule in namespace= with microservice= and databaseType= is empty", "namespace", namespace, "microservice", microservice, "databaseType", databaseType);
            return null;
        }
        if (ruleToApply.get().getRules().get(0).getLabel() == null || ruleToApply.get().getRules().get(0).getLabel().isEmpty()) {
StructuredLog.warn(log, "Rule in namespace= with microservice= and databaseType= has no labels", "namespace", namespace, "microservice", microservice, "databaseType", databaseType);
            return null;
        }
        if (databaseType.equalsIgnoreCase(ruleToApply.get().getType())) {
StructuredLog.info(log, "Rule for namespace, microservice and database type:", "namespace", namespace, "microservice", microservice, "databaseType", databaseType, "ruleToApply", ruleToApply);
            String label = ruleToApply.get().getRules().get(0).getLabel();
            return getCheckPhysicalDatabaseByLabel(label, databaseType);
        } else {
            return null;
        }
    }


    public PhysicalDatabase applyNamespaceBalancingRule(String namespace, String databaseType) {
        PhysicalDatabase physicalDatabase = getDatabaseForNamespace(namespace, databaseType);
        if (physicalDatabase == null) {
            physicalDatabase = getGlobalPhysicalDatabaseIdentifier(databaseType);
            if (physicalDatabase == null) {
StructuredLog.warn(log, "Default physical databases are disabled and no dedicated rules found for namespace and database type", "namespace", namespace, "databaseType", databaseType);
            }
        }
        return physicalDatabase;
    }

    public PhysicalDatabase applyBalancingRules(String type, String namespace, String microserviceName) {
        PhysicalDatabase physicalDatabase = applyMicroserviceBalancingRule(namespace, microserviceName, type);
        if (physicalDatabase == null) {
            log.debug("Per microservice rule is not exist, try to apply per namespace rule");
            physicalDatabase = applyNamespaceBalancingRule(namespace, type);
        }

        if (physicalDatabase == null) {
StructuredLog.error(log, "Unable to determine physical database from rules for microservice '' in namespace ''", "microserviceName", microserviceName, "namespace", namespace);
            throw new NoBalancingRuleException(ErrorCodes.CORE_DBAAS_4033, ErrorCodes.CORE_DBAAS_4033.getDetail(microserviceName, namespace));
        }
        return physicalDatabase;
    }

    private PhysicalDatabase getDatabaseForNamespace(String namespace, String databaseType) {
        List<PerNamespaceRule> perNamespaceRule = balancingRulesDbaasRepository.findByNamespaceAndRuleType(namespace, RuleType.NAMESPACE).stream()
                .filter(rule -> databaseType.equalsIgnoreCase(rule.getDatabaseType())).collect(Collectors.toList());
        Optional<PerNamespaceRule> ruleToApply = perNamespaceRule.stream().max(Comparator.comparingLong(PerNamespaceRule::getOrder));
        if (ruleToApply.isEmpty()) {
            ruleToApply = Optional.ofNullable(balancingRulesDbaasRepository.findPerNamespaceRuleByNamespaceAndDatabaseTypeAndRuleType(namespace, databaseType, RuleType.PERMANENT));
        }
StructuredLog.info(log, "Rule for namespace and database type with max order:", "namespace", namespace, "databaseType", databaseType, "ruleToApply", ruleToApply);
        if (ruleToApply.isPresent()) {
            String physicalDatabaseIdentifier = ruleToApply.get().getPhysicalDatabaseIdentifier();
StructuredLog.info(log, "Physical database identifier:", "physicalDatabaseIdentifier", physicalDatabaseIdentifier);
            PhysicalDatabase physicalDatabase = physicalDatabasesService.getByPhysicalDatabaseIdentifier(physicalDatabaseIdentifier);
            if (physicalDatabase == null) {
                throw new UnregisteredPhysicalDatabaseException("Identifier: " + physicalDatabaseIdentifier);
            }
            return physicalDatabase;
        } else {
            return null;
        }
    }

    @Transactional
    public void removeRulesByNamespace(String namespace) {
        List<PerNamespaceRule> namespaceRules = balancingRulesDbaasRepository.findByNamespace(namespace);
StructuredLog.info(log, "Removing per namespace rules:", "namespaceRules", namespaceRules);
        balancingRulesDbaasRepository.deleteAll(namespaceRules);
    }

    @Transactional
    public boolean deleteNamespaceRule(String namespace, String ruleName) {
        PerNamespaceRule namespaceRule = balancingRulesDbaasRepository.findByName(ruleName);
        if (namespaceRule == null ||
                namespaceRule.getRuleType() != RuleType.NAMESPACE ||
                !namespace.equals(namespaceRule.getNamespace())) {
StructuredLog.info(log, "Per namespace rule was not found in namespace", "ruleName", ruleName, "namespace", namespace);
            return false;
        }
StructuredLog.info(log, "Removing per namespace rule:", "namespaceRule", namespaceRule);
        balancingRulesDbaasRepository.delete(namespaceRule);
        return true;
    }


    @Transactional
    public void removePerMicroserviceRulesByNamespace(String namespace) {
        List<PerMicroserviceRule> namespaceRules = balancingRulesDbaasRepository.findPerMicroserviceByNamespace(namespace);
StructuredLog.info(log, "Removing per microservice rules:", "namespaceRules", namespaceRules);
        balancingRulesDbaasRepository.deleteAllPerMicroserviceRules(namespaceRules);
    }


    public ValidateRulesResponse validateMicroservicesRules(List<OnMicroserviceRuleRequest> onMicroserviceRulesRequest, String namespace) {
        Map<String, String> mapLabelToPhysDb;
        try {
            mapLabelToPhysDb = mapLabelsToPhysicalDatabases(onMicroserviceRulesRequest, namespace);
        } catch (InvalidMicroserviceRuleSizeException | OnMicroserviceBalancingRuleException e) {
StructuredLog.error(log, "Validation of rules on microservices has failed because of:", "arg0", e.getMessage());
            throw e;
        }
        Map<String, String> defaults = collectDefaultDatabases();
        return new ValidateRulesResponse(mapLabelToPhysDb, defaults);
    }

    @NotNull
    Map<String, String> collectDefaultDatabases() {
        Map<String, String> defaults = new HashMap<>();
        List<PhysicalDatabase> allRegisteredDatabases = physicalDatabasesService.getAllRegisteredDatabases();
        for (PhysicalDatabase db : allRegisteredDatabases) {
            if (db.isGlobal()) {
                defaults.put(db.getType(), db.getPhysicalDatabaseIdentifier());
            }
        }
        return defaults;
    }

    @NotNull
    Map<String, String> mapLabelsToPhysicalDatabases(List<OnMicroserviceRuleRequest> onMicroserviceRulesRequest, String namespace) throws InvalidMicroserviceRuleSizeException {
        Map<String, String> mapLabelToPhysDb = new HashMap<>();
        Set<PerMicroserviceRuleKey> processedRules = new HashSet<>();
        for (OnMicroserviceRuleRequest request : onMicroserviceRulesRequest) {
            validateForDuplicates(processedRules, request, namespace);
            if (request.getRules() == null || request.getRules().isEmpty()) {
                throw new InvalidMicroserviceRuleSizeException("Rules should contain any label");
            }
            if (request.getRules().size() > 1) {
                throw new InvalidMicroserviceRuleSizeException("Rules can contain only one label");
            }
            for (RuleOnMicroservice ruleOnMicroservice : request.getRules()) {
                PhysicalDatabase physDbByLabel = getCheckPhysicalDatabaseByLabel(ruleOnMicroservice.getLabel(), request.getType());
                mapLabelToPhysDb.put(ruleOnMicroservice.getLabel(), physDbByLabel.getPhysicalDatabaseIdentifier());
            }
        }
        return mapLabelToPhysDb;
    }

    private Map<String, List<PerMicroserviceRule>> getSavedRulesPerDbMicroservice(String namespace) {
        List<PerMicroserviceRule> rules = balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(namespace);
StructuredLog.debug(log, "rules were found", "rules", rules);
        return rules.stream().collect(Collectors.groupingBy(PerMicroserviceRule::getType));
    }

    private String getPhysicalDatabaseIdentifier(RuleRegistrationRequest ruleRequest) {
        Map<String, Object> perNamespace = (Map<String, Object>) ruleRequest.getRule().getConfig().get(PER_NAMESPACE);
        return (String) perNamespace.get(PHYSICAL_DATABASE_IDENTIFIER);
    }

    private PhysicalDatabase getGlobalPhysicalDatabaseIdentifier(String databaseType) {
        if (defaultPhysicalDatabasesDisabled) {
            return null;
        } else {
StructuredLog.info(log, "Looking for global database of type", "databaseType", databaseType);
            PhysicalDatabase defaultPhysical = physicalDatabasesService.balanceByType(databaseType);
            if (defaultPhysical == null) {
                throw new UnregisteredPhysicalDatabaseException("DB type: " + databaseType);
            }
StructuredLog.info(log, "Default physical database determined:", "defaultPhysical", defaultPhysical);
            return defaultPhysical;
        }
    }

    private Long computeOrder(String namespace, String databaseType) {
        log.info("Computing order for new rule");
        Optional<PerNamespaceRule> namespaceRule =
                balancingRulesDbaasRepository.findByNamespace(namespace).stream()
                        .filter(rule -> databaseType.equals(rule.getDatabaseType()))
                        .max(Comparator.comparingLong(PerNamespaceRule::getOrder));
        return namespaceRule.map(perNamespaceRule -> perNamespaceRule.getOrder() + 1).orElse(0L);
    }


    private void checkPhysicalDatabaseByRequestRule(OnMicroserviceRuleRequest ruleRequest) {
        for (RuleOnMicroservice ruleOnMicroservice : ruleRequest.getRules()) {
            checkPhysicalDatabaseByLabel(ruleOnMicroservice.getLabel(), ruleRequest.getType());
        }
    }

    private void checkPhysicalDatabaseByLabel(String requestLabels, String type) {
        if (requestLabels != null) {
            getCheckPhysicalDatabaseByLabel(requestLabels, type);
        }
    }

    PhysicalDatabase getCheckPhysicalDatabaseByLabel(String requestLabels, String type) {

StructuredLog.info(log, "Try to find physical database with label:", "requestLabels", requestLabels);

        String[] labelArray = parsePerMicroserviceRequestLabel(requestLabels);

        List<PhysicalDatabase> physicalDatabases = physicalDatabasesService.getPhysicalDatabaseContainsLabel(labelArray[0], labelArray[1], type);
        if (physicalDatabases == null || physicalDatabases.isEmpty()) {
            throw new OnMicroserviceBalancingRuleException("Physical database " +
                    "with label: " + requestLabels + " not registered ");
        }
        if (physicalDatabases.size() != 1) {
            throw new OnMicroserviceBalancingRuleException("More than one physical database registered " +
                    "with label: " + requestLabels);
        }

        return physicalDatabases.get(0);

    }

    private String[] parsePerMicroserviceRequestLabel(String requestLabels) {
        String[] labelArray = requestLabels.split("=");
        if (labelArray.length != 2 || labelArray[0].contains("=") || labelArray[1].contains("=")) {
            throw new OnMicroserviceBalancingRuleException("Labels=" + requestLabels + " from request incorrect");
        }
        return labelArray;
    }


    public List<PermanentPerNamespaceRuleDTO> getPermanentOnNamespaceRule(String namespace) {
        return getPermanentPerNamespaceRuleDTOS(balancingRulesDbaasRepository.findByNamespaceAndRuleType(namespace, RuleType.PERMANENT));
    }

    public List<PermanentPerNamespaceRuleDTO> getPermanentOnNamespaceRule() {
        return getPermanentPerNamespaceRuleDTOS(balancingRulesDbaasRepository.findByRuleType(RuleType.PERMANENT));
    }

    public List<PerNamespaceRule> copyNamespaceRule(String sourceNamespace, String targetNamespace) {
StructuredLog.info(log, "Copy namespace rules from namespace to", "sourceNamespace", sourceNamespace, "targetNamespace", targetNamespace);
        List<PerNamespaceRule> namespaceRules =
                balancingRulesDbaasRepository.findAllRulesByNamespace(sourceNamespace).stream().map(rule -> {
                    PerNamespaceRule perNamespaceRule = new PerNamespaceRule(rule);
                    perNamespaceRule.setNamespace(targetNamespace);
                    perNamespaceRule.setName(rule.getName() + "_" + UUID.randomUUID());
                    return perNamespaceRule;
                }).collect(Collectors.toList());
StructuredLog.debug(log, "Copied namespaceRules =", "namespaceRules", namespaceRules);
        return balancingRulesDbaasRepository.saveAllNamespaceRules(namespaceRules);
    }

    public List<PerMicroserviceRule> copyMicroserviceRule(String sourceNamespace, String targetNamespace) {
StructuredLog.info(log, "Copy microservice rules from namespace to", "sourceNamespace", sourceNamespace, "targetNamespace", targetNamespace);

        int maxGeneration = -1;
        List<PerMicroserviceRule> rulesToSave = new ArrayList<>();

        List<PerMicroserviceRule> microserviceRules =
                balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(sourceNamespace).stream().map(rule -> {
                    PerMicroserviceRule perMicroserviceRule = new PerMicroserviceRule(rule);
                    perMicroserviceRule.setNamespace(targetNamespace);
                    return perMicroserviceRule;
                }).collect(Collectors.toList());

        List<PerMicroserviceRule> existedRules = balancingRulesDbaasRepository.findPerMicroserviceByNamespaceWithMaxGeneration(targetNamespace);
        for (PerMicroserviceRule rule : microserviceRules) {
            if (existedRules != null) {
                Optional<PerMicroserviceRule> specificRuleToMicroservice = existedRules.stream().
                        filter(existedRule -> existedRule.getMicroservice().equals(rule.getMicroservice()) && existedRule.getType().equals(rule.getType()))
                        .max(Comparator.comparing(PerMicroserviceRule::getGeneration));
                if (specificRuleToMicroservice.isPresent()) {
                    PerMicroserviceRule existedRule = specificRuleToMicroservice.get();
                    rule.setCreateDate(existedRule.getCreateDate());
                    rule.setUpdateDate(existedRule.getUpdateDate());
                    maxGeneration = existedRule.getGeneration() > maxGeneration ? existedRule.getGeneration() : maxGeneration;
                }
            }

            rulesToSave.add(rule);
        }

        int nextGeneration = maxGeneration + 1;
        rulesToSave.forEach(perMicroserviceRule -> perMicroserviceRule.setGeneration(nextGeneration));

        return balancingRulesDbaasRepository.saveAll(rulesToSave);
    }


    @NotNull
    private List<PermanentPerNamespaceRuleDTO> getPermanentPerNamespaceRuleDTOS(List<PerNamespaceRule> permanentRules) {
        Map<String, PermanentPerNamespaceRuleDTO> resultMap = new HashMap<>();
        for (PerNamespaceRule rule : permanentRules) {
            String physDbId = rule.getPhysicalDatabaseIdentifier();
            if (resultMap.containsKey(physDbId)) {
                PermanentPerNamespaceRuleDTO dto = resultMap.get(physDbId);
                dto.getNamespaces().add(rule.getNamespace());
                resultMap.put(physDbId, dto);
            } else {
                Set<String> namespaces = new HashSet<>();
                namespaces.add(rule.getNamespace());
                resultMap.put(physDbId, new PermanentPerNamespaceRuleDTO(rule.getDatabaseType(), rule.getPhysicalDatabaseIdentifier(), namespaces));
            }
        }
        return new ArrayList<>(resultMap.values());
    }

    @Transactional
    public void deletePermanentRules(List<PermanentPerNamespaceRuleDeleteDTO> rulesToDelete) {
        for (PermanentPerNamespaceRuleDeleteDTO rule : rulesToDelete) {
            for (String namespace : rule.getNamespaces()) {
                if (rule.getDbType() == null || rule.getDbType().isEmpty()) {
                    balancingRulesDbaasRepository.deleteByNamespaceAndRuleType(namespace, RuleType.PERMANENT);
                } else {
                    balancingRulesDbaasRepository.deleteByNamespaceAndDbTypeAndRuleType(namespace, rule.getDbType(), RuleType.PERMANENT);
                }
            }
        }
    }

    public Map<String, Map<String, DebugRulesDbTypeData>> debugBalancingRules(List<OnMicroserviceRuleRequest> newRequestRules,
                                                                              String namespace, List<String> microservices) {
        Map<String, Map<String, DebugRulesDbTypeData>> responseMap = new HashMap<>();
        Map<String, List<PerMicroserviceRule>> existedMicroserviceRules = getSavedRulesPerDbMicroservice(namespace);
        List<PerMicroserviceRule> newRules = convertToEntity(newRequestRules, namespace, existedMicroserviceRules);
        Set<String> dbTypes = physicalDatabasesService.getAllRegisteredDatabases().stream().map(physicalDatabase -> physicalDatabase.getType()).collect(Collectors.toSet());
        for (String microservice : microservices) {
            Map<String, DebugRulesDbTypeData> dbTypeToPhysDbMap = new HashMap<>();
            for (String databaseType : dbTypes) {
                Stream<PerMicroserviceRule> filteredRules = newRules.stream()
                        .filter(rule -> namespace.equals(rule.getNamespace()) && microservice.equals(rule.getMicroservice()) && databaseType.equals(rule.getType()));
                List<PerMicroserviceRule> rulesToApply = Stream.concat(
                        filteredRules,
                        balancingRulesDbaasRepository.findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(namespace, microservice, databaseType).stream()
                ).toList();
                PhysicalDatabase assignedDatabase = processMicroserviceBalancingRules(namespace, microservice, databaseType, rulesToApply);
                if (assignedDatabase != null) {
                    dbTypeToPhysDbMap.put(databaseType, new DebugRulesDbTypeData(assignedDatabase.getLabels(), assignedDatabase.getPhysicalDatabaseIdentifier(), DebugRulesDbTypeData.MICROSERVICE_RULE_INFO));
                    continue;
                }

                assignedDatabase = getDatabaseForNamespace(namespace, databaseType);
                if (assignedDatabase != null) {
                    dbTypeToPhysDbMap.put(databaseType, new DebugRulesDbTypeData(assignedDatabase.getLabels(), assignedDatabase.getPhysicalDatabaseIdentifier(), DebugRulesDbTypeData.NAMESPACE_RULE_INFO));
                    continue;
                }

                assignedDatabase = getGlobalPhysicalDatabaseIdentifier(databaseType);
                if (assignedDatabase != null) {
                    dbTypeToPhysDbMap.put(databaseType, new DebugRulesDbTypeData(assignedDatabase.getLabels(), assignedDatabase.getPhysicalDatabaseIdentifier(), DebugRulesDbTypeData.DEFAULT_DATABASE_RULE_INFO));
                    continue;
                }
                dbTypeToPhysDbMap.put(databaseType, new DebugRulesDbTypeData(null, null, DebugRulesDbTypeData.NO_SUITABLE_DATABASE_RULE_INFO));
            }
            responseMap.put(microservice, dbTypeToPhysDbMap);
        }
        return responseMap;
    }

    private void validateForDuplicates(Set<PerMicroserviceRuleKey> processedRules, OnMicroserviceRuleRequest request, String namespace) {
        for (String microservice : request.getMicroservices()) {
            PerMicroserviceRuleKey key = new PerMicroserviceRuleKey(microservice, namespace, request.getType());
            if (processedRules.contains(key)) {
                throw new OnMicroserviceBalancingRuleDuplicateException(String.format("Duplicate rule found for microservice=%s and type=%s in namespace=%s", microservice, request.getType(), namespace));
            } else {
                processedRules.add(key);
            }
        }
    }

    public boolean areRulesExistingInNamespace(String namespace) {
        return !balancingRulesDbaasRepository.findPerMicroserviceByNamespace(namespace).isEmpty() ||
                !balancingRulesDbaasRepository.findByNamespace(namespace).isEmpty();
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    private static class PerMicroserviceRuleKey {
        private String microservice;
        private String namespace;
        private String dbType;
    }
}
