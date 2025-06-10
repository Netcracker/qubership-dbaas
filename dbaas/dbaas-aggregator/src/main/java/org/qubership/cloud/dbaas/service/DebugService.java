package org.qubership.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.RuleType;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.*;
import org.qubership.cloud.dbaas.mapper.DebugLogicalDatabaseMapper;
import org.qubership.cloud.dbaas.entity.dto.DebugLogicalDatabasePersistenceDto;
import org.qubership.cloud.dbaas.repositories.queries.DebugLogicalDatabaseQueries;
import org.qubership.cloud.dbaas.repositories.queries.NamespaceSqlQueries;
import org.qubership.cloud.dbaas.rsql.config.DebugGetLogicalDatabasesRSQLConfig;
import org.qubership.cloud.dbaas.rsql.QueryPreparationRSQLProcessor;
import org.qubership.cloud.dbaas.rsql.QueryPreparationRSQLVisitor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@ApplicationScoped
public class DebugService {

    public static final String DUMP_JSON_FILENAME = "dbaas_dump.json";
    public static final String WHERE_CLAUSE = " WHERE ";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    private final Predicate<? super PerNamespaceRule> permanentRulesFilter =
        rule -> RuleType.PERMANENT.equals(rule.getRuleType());

    private final QueryPreparationRSQLProcessor queryPreparationRSQLProcessor =
        new QueryPreparationRSQLProcessor(new QueryPreparationRSQLVisitor());

    private final BalancingRulesRepository namespaceRulesRepository;
    private final BalanceRulesRepositoryPerMicroservice microserviceRulesRepository;
    private final PhysicalDatabasesRepository physicalDatabasesRepository;
    private final DatabasesRepository databasesRepository;
    private final DatabaseDeclarativeConfigRepository databaseDeclarativeConfigRepository;
    private final BgDomainRepository bgDomainRepository;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final LogicalDbDbaasRepository logicalDbDbaasRepository;
    private final ResponseHelper responseHelper;
    private final HealthService healthService;
    private final DebugLogicalDatabaseMapper debugLogicalDatabaseMapper;

    @Inject
    DebugService(BalancingRulesRepository namespaceRulesRepository,
                 BalanceRulesRepositoryPerMicroservice microserviceRulesRepository,
                 PhysicalDatabasesRepository physicalDatabasesRepository,
                 DatabasesRepository databasesRepository,
                 DatabaseDeclarativeConfigRepository databaseDeclarativeConfigRepository,
                 BgDomainRepository bgDomainRepository,
                 PhysicalDatabasesService physicalDatabasesService,
                 LogicalDbDbaasRepository logicalDbDbaasRepository,
                 ResponseHelper responseHelper,
                 HealthService healthService,
                 DebugLogicalDatabaseMapper debugLogicalDatabaseMapper) {
        this.namespaceRulesRepository = namespaceRulesRepository;
        this.microserviceRulesRepository = microserviceRulesRepository;
        this.physicalDatabasesRepository = physicalDatabasesRepository;
        this.databasesRepository = databasesRepository;
        this.databaseDeclarativeConfigRepository = databaseDeclarativeConfigRepository;
        this.bgDomainRepository = bgDomainRepository;
        this.physicalDatabasesService = physicalDatabasesService;
        this.logicalDbDbaasRepository = logicalDbDbaasRepository;
        this.responseHelper = responseHelper;
        this.healthService = healthService;
        this.debugLogicalDatabaseMapper = debugLogicalDatabaseMapper;

        // to make Jackson to not close OutputStream passed to ObjectMapper#writeValue method
        objectMapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
    }

    @Transactional
    public DumpResponseV3 loadDumpV3() {
        try {
            log.info("Start loading dump from DBaaS database");

            var physicalDatabasesTask = executorService.submit(() -> physicalDatabasesRepository.listAll());
            var logicalDatabasesTask = executorService.submit(() -> databasesRepository.listAll());
            var declarativeConfigsTask = executorService.submit(() -> databaseDeclarativeConfigRepository.listAll());
            var blueGreenDomainsTask = executorService.submit(() -> bgDomainRepository.listAll());
            var namespaceAndPermanentRulesTask = executorService.submit(() -> namespaceRulesRepository.listAll());
            var microserviceRulesTask = executorService.submit(() -> microserviceRulesRepository.listAll());

            log.info("Parallel loading dump from DBaaS database is started");

            var physicalDatabases = physicalDatabasesTask.get();
            var logicalDatabases = logicalDatabasesTask.get();
            var declarativeConfigs = declarativeConfigsTask.get();
            var blueGreenDomains = blueGreenDomainsTask.get();
            var namespaceAndPermanentRules = namespaceAndPermanentRulesTask.get();
            var microserviceRules = microserviceRulesTask.get();

            log.info("Parallel loading dump from DBaaS database is finished");

            var dumpRules = convertDumpRulesV3(physicalDatabases, namespaceAndPermanentRules, microserviceRules);

            log.info("Finish loading dump from DBaaS database");
            return new DumpResponseV3(
                dumpRules, logicalDatabases, physicalDatabases, declarativeConfigs, blueGreenDomains
            );
        } catch (InterruptedException | ExecutionException ex) {
            Thread.currentThread().interrupt();

            throw new RuntimeException("Error happened during loading dump from DBaaS database: " + ex.getMessage(), ex);
        }
    }

    public StreamingOutput getStreamingOutputSerializingDumpToZippedJsonFile(DumpResponseV3 dumpResponse) {
        return outputStream -> {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
                zipOutputStream.putNextEntry(new ZipEntry(DUMP_JSON_FILENAME));

                objectMapper.writeValue(zipOutputStream, dumpResponse);

                zipOutputStream.closeEntry();
                zipOutputStream.finish();
            }
        };
    }

    @SuppressWarnings("unchecked")
    public List<DebugLogicalDatabaseV3> findDebugLogicalDatabases(String filterRsqlQuery) {
        var queryPreparation = queryPreparationRSQLProcessor.parseAndConstruct(
            filterRsqlQuery, DebugGetLogicalDatabasesRSQLConfig.OVERRIDE_CONFIG
        );
        var preparedQuery = queryPreparation.prepareQuery();

        var query = DebugLogicalDatabaseQueries.FIND_DEBUG_LOGICAL_DATABASES;
        var filterQuery = preparedQuery.getQuery();

        if (StringUtils.isNotBlank(filterQuery)) {
            query = query + WHERE_CLAUSE + filterQuery;
        }

        var nativeQuery = databasesRepository.getEntityManager()
            .createNativeQuery(query, DebugLogicalDatabasePersistenceDto.class);

        MapUtils.emptyIfNull(preparedQuery.getParameterNamesAndValues())
            .forEach(nativeQuery::setParameter);

        var debugLogicalDatabases = nativeQuery.getResultList();

        return debugLogicalDatabaseMapper.convertDebugLogicalDatabases(debugLogicalDatabases);
    }

    protected DumpRulesV3 convertDumpRulesV3(List<PhysicalDatabase> physicalDatabases,
                                             List<PerNamespaceRule> namespaceAndPermanentRules,
                                             List<PerMicroserviceRule> microserviceRules) {

        var defaultRules = physicalDatabases.stream()
            .filter(database -> Boolean.TRUE.equals(database.isGlobal()))
            .map(this::convertDumpDefaultRuleV3)
            .toList();

        var namespaceRules = namespaceAndPermanentRules.stream()
            .filter(permanentRulesFilter.negate())
            .toList();

        var permanentRules = namespaceAndPermanentRules.stream()
            .filter(permanentRulesFilter)
            .toList();

        return new DumpRulesV3(
            defaultRules, namespaceRules, microserviceRules, permanentRules
        );
    }

    protected DumpDefaultRuleV3 convertDumpDefaultRuleV3(PhysicalDatabase physicalDatabase) {
        var address = Optional.ofNullable(physicalDatabase.getAdapter())
            .map(ExternalAdapterRegistrationEntry::getAddress)
            .orElse(null);

        return new DumpDefaultRuleV3(physicalDatabase.getId(), address);
    }

    @PreDestroy
    public void cleanUp() {
        log.info("Start shutting down executor service");

        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                log.info("Executor service is still not terminated");

                executorService.shutdownNow();

                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("Executor service was not terminated even after await");
                }
            }
        } catch (InterruptedException ex) {
            log.error("Error happened during shutting down executor service: ", ex);

            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Finish shutting down executor service");
    }

    public List<LostDatabasesResponse> findLostDatabases() {
        List<LostDatabasesResponse> lostDatabasesList = new ArrayList<>();
        List<DatabaseRegistry> registered = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllInternalDatabases();
        Map<String, List<DatabaseRegistry>> registeredDatabasesByPhisicalId = registered.stream()
                .collect(Collectors.groupingBy(DatabaseRegistry::getPhysicalDatabaseId));
        for (DbaasAdapter adapter : physicalDatabasesService.getAllAdapters()) {
            if (Boolean.TRUE.equals(adapter.isDisabled())) continue;
            LostDatabasesResponse lostDatabases = new LostDatabasesResponse();
            String physicalDatabaseId = physicalDatabasesService.getByAdapterId(adapter.identifier()).getPhysicalDatabaseIdentifier();
            lostDatabases.setPhysicalDatabaseId(physicalDatabaseId);
            try {
                Set<String> adapterRealDatabases = Optional.ofNullable(adapter.getDatabases()).orElse(Collections.emptySet());
                List<DatabaseRegistry> lost = Optional.ofNullable(registeredDatabasesByPhisicalId.get(physicalDatabaseId))
                        .orElse(Collections.emptyList())
                        .stream()
                        .filter(entry -> !adapterRealDatabases.contains(entry.getName()))
                        .toList();

                lostDatabases.setDatabases(responseHelper.toDatabaseResponse(lost, false));
            } catch (Exception e) {
                String message = e.getMessage() == null || e.getMessage().isEmpty() ?
                        String.format("Error happened during request to %s for getting databases", adapter.adapterAddress()) :
                        String.format("Error happened during request to %s for getting databases: %s", adapter.adapterAddress(), e.getMessage());
                log.error(message);
                lostDatabases.setErrorMessage(message);
            }
            if (lostDatabases.getDatabases() == null) {
                lostDatabases.setDatabases(Collections.emptyList());
            }
            lostDatabasesList.add(lostDatabases);
        }
        return lostDatabasesList;
    }

    public List<GhostDatabasesResponse> findGhostDatabases() {
        List<DatabaseRegistry> registered = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllInternalDatabases();
        List<String> registeredDatabaseNames = registered.stream()
                .map(DatabaseRegistry::getName)
                .toList();
        List<GhostDatabasesResponse> ghost = new ArrayList<>();
        for (DbaasAdapter adapter : physicalDatabasesService.getAllAdapters()) {
            if (Boolean.TRUE.equals(adapter.isDisabled())) continue;
            GhostDatabasesResponse ghostDatabasesResponse = new GhostDatabasesResponse();
            ghostDatabasesResponse.setPhysicalDatabaseId(physicalDatabasesService.getByAdapterId(adapter.identifier()).getPhysicalDatabaseIdentifier());
            try {
                Set<String> adapterRealDatabases = Optional.ofNullable(adapter.getDatabases()).orElse(Collections.emptySet());
                Set<String> adapterGhost = adapterRealDatabases.stream()
                        .filter(dbName -> !registeredDatabaseNames.contains(dbName))
                        .collect(Collectors.toSet());
                ghostDatabasesResponse.setDbNames(new ArrayList<>(adapterGhost));
                ghost.add(ghostDatabasesResponse);
            } catch (Exception e) {
                String message = e.getMessage() == null || e.getMessage().isEmpty() ?
                        String.format("Error happened during request to %s for getting databases", adapter.adapterAddress()) :
                        String.format("Error happened during request to %s for getting databases: %s", adapter.adapterAddress(), e.getMessage());
                log.error(message);
                ghostDatabasesResponse.setErrorMessage(message);
            }
            if (ghostDatabasesResponse.getDbNames() == null) {
                ghostDatabasesResponse.setDbNames(Collections.emptyList());
            }
        }
        return ghost;
    }

    public OverallStatusResponse getOverallStatus() {
        OverallStatusResponse overallStatusResponse = new OverallStatusResponse();
        String healthStatus = String.valueOf(healthService.getHealth().getStatus());
        overallStatusResponse.setOverallHealthStatus(healthStatus);
        Integer logicalDbNumber = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllInternalDatabases().size();
        overallStatusResponse.setOverallLogicalDbNumber(logicalDbNumber);
        List<PhysicalDatabaseInfo> physicalDatabaseInfoList = new ArrayList<>();
        for (DbaasAdapter adapter : physicalDatabasesService.getAllAdapters()) {
            if (Boolean.TRUE.equals(adapter.isDisabled())) continue;
            PhysicalDatabaseInfo physicalDatabaseInfo = new PhysicalDatabaseInfo();
            String physicalDatabaseId = physicalDatabasesService.getByAdapterId(adapter.identifier()).getPhysicalDatabaseIdentifier();
            physicalDatabaseInfo.setPhysicalDatabaseId(physicalDatabaseId);
            Set<String> adapterRealDatabases;
            AdapterHealthStatus health = adapter.getAdapterHealth();
            physicalDatabaseInfo.setHealthStatus(health.getStatus());
            try {
                adapterRealDatabases = Optional.ofNullable(adapter.getDatabases()).orElse(Collections.emptySet());
            } catch (Exception e) {
                String message = e.getMessage() == null || e.getMessage().isEmpty() ?
                        String.format("Error happened during request to %s for getting databases", adapter.adapterAddress()) :
                        String.format("Error happened during request to %s for getting databases: %s", adapter.adapterAddress(), e.getMessage());
                log.error(message);
                physicalDatabaseInfo.setLogicalDbNumber("ERROR");
                physicalDatabaseInfoList.add(physicalDatabaseInfo);
                continue;
            }
            physicalDatabaseInfo.setLogicalDbNumber(String.valueOf(adapterRealDatabases.size()));
            physicalDatabaseInfoList.add(physicalDatabaseInfo);
        }
        overallStatusResponse.setPhysicalDatabaseInfoList(physicalDatabaseInfoList);
        return overallStatusResponse;
    }

    @SuppressWarnings("unchecked")
    public List<String> findAllRegisteredNamespaces() {
        log.info("Loading all registered namespaces");

        var nativeQuery = databasesRepository.getEntityManager().createNativeQuery(
            NamespaceSqlQueries.FIND_ALL_REGISTERED_NAMESPACES, String.class
        );

        var namespaces = (List<String>) nativeQuery.getResultList();

        log.info("Loaded all {} registered namespaces", namespaces.size());

        return namespaces;
    }
}
