package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.backup.NamespaceBackupDeletion;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.BgStateRequest;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseToDeclarativeCreation;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgDomainRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgTrackRepository;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.controller.v3.AggregatedBackupAdministrationControllerV3.DBAAS_PATH;
import static org.qubership.cloud.dbaas.service.DBaaService.MARKED_FOR_DROP;
import static org.qubership.cloud.dbaas.service.DatabaseConfigurationCreationService.DatabaseExistence;

@ApplicationScoped
@Slf4j
public class BlueGreenService {

    final DeclarativeDbaasCreationService declarativeDbaasCreationService;

    final DBBackupsService backupsService;

    final LogicalDbDbaasRepository logicalDbDbaasRepository;

    final AggregatedDatabaseAdministrationService aggregatedDatabaseAdministrationService;

    final BgNamespaceRepository bgNamespaceRepository;

    final BgDomainRepository bgDomainRepository;

    final BalancingRulesService balancingRulesService;

    final DatabaseRolesService databaseRolesService;

    final DBaaService dBaaService;

    final BgTrackRepository bgTrackRepository;

    final ProcessService processService;

    final BackupsDbaasRepository backupsDbaasRepository;

    final
    DatabaseConfigurationCreationService databaseConfigurationCreationService;

    private final Random random = new Random();

    // todo arvo: when there are plenty dependencies it means bad composition and we should think about composition
    public BlueGreenService(
            DBBackupsService backupsService,
            LogicalDbDbaasRepository logicalDbDbaasRepository,
            AggregatedDatabaseAdministrationService aggregatedDatabaseAdministrationService,
            BgNamespaceRepository bgNamespaceRepository,
            BalancingRulesService balancingRulesService,
            DatabaseRolesService databaseRolesService,
            BgDomainRepository bgDomainRepository,
            DBaaService dBaaService,
            BgTrackRepository bgTrackRepository,
            ProcessService processService,
            DeclarativeDbaasCreationService declarativeDbaasCreationService, BackupsDbaasRepository backupsDbaasRepository,
            DatabaseConfigurationCreationService databaseConfigurationCreationService) {
        this.backupsService = backupsService;
        this.logicalDbDbaasRepository = logicalDbDbaasRepository;
        this.aggregatedDatabaseAdministrationService = aggregatedDatabaseAdministrationService;
        this.bgNamespaceRepository = bgNamespaceRepository;
        this.balancingRulesService = balancingRulesService;
        this.databaseRolesService = databaseRolesService;
        this.bgDomainRepository = bgDomainRepository;
        this.dBaaService = dBaaService;
        this.bgTrackRepository = bgTrackRepository;
        this.processService = processService;
        this.declarativeDbaasCreationService = declarativeDbaasCreationService;
        this.backupsDbaasRepository = backupsDbaasRepository;
        this.databaseConfigurationCreationService = databaseConfigurationCreationService;
    }


    public ProcessInstanceImpl warmup(BgStateRequest.BGState bgState) {
        BgNamespace requestedCandidateNamespace = new BgNamespace(bgState.getBgNamespaceWithState(CANDIDATE_STATE).orElseThrow(), bgState.getUpdateTime());

        BgNamespace requestedActiveNamespace = new BgNamespace(bgState.getBgNamespaceWithState(ACTIVE_STATE).orElseThrow(), bgState.getUpdateTime());

        BgNamespace idleBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(requestedCandidateNamespace.getNamespace())
                .orElseThrow(() -> new BgRequestValidationException("Can't find idle namespace for namespace"));


        BgNamespace activeBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(requestedActiveNamespace.getNamespace())
                .orElseThrow(() -> new BgRequestValidationException("Can't find active namespace for namespace"));


        if (CANDIDATE_STATE.equals(idleBgNamespace.getState()) && ACTIVE_STATE.equals(activeBgNamespace.getState())) {
            return null;
        }
        if (!ACTIVE_STATE.equals(activeBgNamespace.getState()) || !IDLE_STATE.equals(idleBgNamespace.getState())) {
            throw new BgRequestValidationException("Only Active + Idle namespaces are allowed for warmup");
        }

        String activeNamespace = activeBgNamespace.getNamespace();
        if (activeNamespace.equals(requestedCandidateNamespace.getNamespace())) {
            throw new BgRequestValidationException("You can warmup only candidate namespace");
        }
        Optional<BgTrack> trackOptional = bgTrackRepository.findByNamespaceAndOperation(requestedCandidateNamespace.getNamespace(), WARMUP_OPERATION);
        if (trackOptional.isPresent()) {
            ProcessInstanceImpl process = processService.getProcess(trackOptional.get().getId());
            if (process != null) {
                TaskState state = process.getState();
                if (TaskState.IN_PROGRESS.equals(state) || TaskState.NOT_STARTED.equals(state)) {
                    return process;
                } else if (TaskState.TERMINATED.equals(state) || TaskState.FAILED.equals(state)) {
                    processService.retryProcess(process);
                    return process;
                }
            }
        }

        copyNamespaceRules(activeNamespace, requestedCandidateNamespace.getNamespace());
        copyMicroserviceRules(activeNamespace, requestedCandidateNamespace.getNamespace());
        copyDatabaseRoles(activeNamespace, requestedCandidateNamespace.getNamespace());

        List<DatabaseDeclarativeConfig> declarativeConfigs = declarativeDbaasCreationService.findAllByNamespace(activeNamespace);

        ArrayList<DatabaseToDeclarativeCreation> configsToCreateDatabase = new ArrayList<>();


        for (DatabaseDeclarativeConfig databaseDeclarativeConfig : declarativeConfigs) {
            DatabaseDeclarativeConfig newConfiguration = declarativeDbaasCreationService.saveConfigurationWithNewNamespace(databaseDeclarativeConfig,
                    requestedCandidateNamespace.getNamespace());
            if (Boolean.TRUE.equals(newConfiguration.getLazy())) {
                continue;
            }
            DatabaseExistence allDatabaseExists = databaseConfigurationCreationService.isAllDatabaseExists(newConfiguration,
                    requestedActiveNamespace.getNamespace());
            if (allDatabaseExists.isExist() && allDatabaseExists.isActual()) {
                log.debug("all databases with configuration = {} are exists", newConfiguration);
                continue;
            }
            configsToCreateDatabase.add(new DatabaseToDeclarativeCreation(newConfiguration, false,
                    new TreeMap<>(databaseDeclarativeConfig.getClassifier())));

        }
        ArrayList<AbstractDatabaseProcessObject> processObjects = new ArrayList<>();
        configsToCreateDatabase.forEach(config -> {
            processObjects.addAll(databaseConfigurationCreationService.createDatabaseProcessObject(config.getDatabaseDeclarativeConfig(), config.getCloneToNew(),
                    config.getSourceClassifier(), Optional.of(requestedCandidateNamespace), WARMUP_OPERATION));
        });
        ProcessInstanceImpl process = databaseConfigurationCreationService.createProcessInstance(processObjects,
                WARMUP_OPERATION, requestedCandidateNamespace.getNamespace(), requestedCandidateNamespace.getVersion());

        warmupStaticDatabases(requestedCandidateNamespace.getNamespace(), requestedActiveNamespace.getNamespace());

        processService.startProcess(process);

        return process;
    }

    private void warmupStaticDatabases(String candidateNamespace, String activeNamespace) {
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(activeNamespace)
                .stream().filter(dbr -> dbr.getBgVersion() == null && !dbr.getClassifier().containsKey(MARKED_FOR_DROP))
                .forEach(staticDatabaseRegistry -> dBaaService.shareDbToNamespace(staticDatabaseRegistry, candidateNamespace));
    }

    private BgNamespace updateBgNamespace(BgNamespace candidateBgNamespace, String newState, String version) {
        candidateBgNamespace.setState(newState);
        candidateBgNamespace.setVersion(version);
        candidateBgNamespace.setUpdateTime(new Date());
        bgNamespaceRepository.persist(candidateBgNamespace);
        return candidateBgNamespace;
    }

    private void copyDatabaseRoles(String sourceNamespace, String targetNamespace) {
        databaseRolesService.copyDatabaseRole(sourceNamespace, targetNamespace);
    }

    private void copyMicroserviceRules(String sourceNamespace, String targetNamespace) {
        balancingRulesService.copyMicroserviceRule(sourceNamespace, targetNamespace);

    }

    private void copyNamespaceRules(String sourceNamespace, String targetNamespace) {
        balancingRulesService.copyNamespaceRule(sourceNamespace, targetNamespace);
    }

    public Optional<BgDomain> getBgDomainContains(String namespace) {
        return bgNamespaceRepository.findBgNamespaceByNamespace(namespace).map(BgNamespace::getBgDomain);
    }

    public void initBgDomain(BgStateRequest bgStateRequest) {

        BgStateRequest.BGStateNamespace bgNamespace1 = bgStateRequest.getBGState().getOriginNamespace();
        BgStateRequest.BGStateNamespace bgNamespace2 = bgStateRequest.getBGState().getPeerNamespace();
        String controllerNamespace = bgStateRequest.getBGState().getControllerNamespace();
        if (StringUtils.isEmpty(bgNamespace1.getName()) || StringUtils.isEmpty(bgNamespace2.getName()) || StringUtils.isEmpty(controllerNamespace)) {
            throw new BgRequestValidationException(String.format("Namespace name cannot be empty, but: origin='%s', peer='%s', controller='%s'",
                    bgNamespace1.getName(), bgNamespace2.getName(), controllerNamespace));
        }
        if (!(bgNamespace1.getState().equals(ACTIVE_STATE) && bgNamespace2.getState().equals(IDLE_STATE) ||
                bgNamespace1.getState().equals(IDLE_STATE) && bgNamespace2.getState().equals(ACTIVE_STATE))) {
            throw new BgRequestValidationException(String.format("States of bgRequest must be active and idle, but were %s and %s",
                    bgNamespace1.getState(), bgNamespace2.getState()));
        }

        Optional<BgNamespace> foundedBgNamespace1 = bgNamespaceRepository.findBgNamespaceByNamespace(bgNamespace1.getName());
        Optional<BgNamespace> foundedBgNamespace2 = bgNamespaceRepository.findBgNamespaceByNamespace(bgNamespace2.getName());
        if (foundedBgNamespace1.isPresent() ^ foundedBgNamespace2.isPresent()) {
            throw new BgRequestValidationException("One of requested namespaces already used in another bgDomain");
        }
        if (foundedBgNamespace1.isPresent() && foundedBgNamespace2.isPresent() &&
                !foundedBgNamespace1.get().getBgDomain().equals(foundedBgNamespace2.get().getBgDomain())) {
            throw new BgRequestValidationException("These namespaces already belongs to different bgDomains");

        }
        if (foundedBgNamespace1.isPresent() && foundedBgNamespace2.isPresent() &&
                foundedBgNamespace1.get().getBgDomain().equals(foundedBgNamespace2.get().getBgDomain())) {
            if (!(Objects.equals(bgNamespace1.getState(), foundedBgNamespace1.get().getState()) && Objects.equals(bgNamespace2.getState(), foundedBgNamespace2.get().getState())) ||
                    !(Objects.equals(bgNamespace1.getVersion(), foundedBgNamespace1.get().getVersion()) && Objects.equals(bgNamespace2.getVersion(), foundedBgNamespace2.get().getVersion()))) {
                throw new BgRequestValidationException("Existing namespaces in the different states");
            }
            BgDomain bgDomain = foundedBgNamespace1.get().getBgDomain();
            if (StringUtils.isEmpty(bgDomain.getControllerNamespace())
                    || StringUtils.isEmpty(bgDomain.getOriginNamespace())
                    || StringUtils.isEmpty(bgDomain.getPeerNamespace())) {
                bgDomain.setControllerNamespace(bgStateRequest.getBGState().getControllerNamespace());
                bgDomain.setOriginNamespace(bgStateRequest.getBGState().getOriginNamespace().getName());
                bgDomain.setPeerNamespace(bgStateRequest.getBGState().getPeerNamespace().getName());
                bgDomainRepository.persist(bgDomain);
            }
            return;
        }
        List<DatabaseRegistry> peerDbRegistries = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .findAnyLogDbRegistryTypeByNamespace(bgStateRequest.getBGState().getBgNamespaceWithState(IDLE_STATE).orElseThrow().getName());
        if (peerDbRegistries.stream().anyMatch(Predicate.not(DatabaseRegistry::isMarkedForDrop))) {
            throw new BgRequestValidationException("Peer namespace contains DBs");
        }

        BgDomain bgDomain = new BgDomain();
        BgNamespace targetNamespace1 = new BgNamespace(bgStateRequest.getBGState().getOriginNamespace(), bgStateRequest.getBGState().getUpdateTime());
        BgNamespace targetNamespace2 = new BgNamespace(bgStateRequest.getBGState().getPeerNamespace(), bgStateRequest.getBGState().getUpdateTime());
        targetNamespace1.setBgDomain(bgDomain);
        targetNamespace2.setBgDomain(bgDomain);
        bgDomain.setNamespaces(List.of(targetNamespace1, targetNamespace2));
        bgDomain.setControllerNamespace(bgStateRequest.getBGState().getControllerNamespace());
        bgDomain.setOriginNamespace(bgStateRequest.getBGState().getOriginNamespace().getName());
        bgDomain.setPeerNamespace(bgStateRequest.getBGState().getPeerNamespace().getName());
        bgDomainRepository.persist(bgDomain);

        BgNamespace activeNamespace;
        if (ACTIVE_STATE.equals(targetNamespace1.getState())) {
            activeNamespace = targetNamespace1;
        } else {
            activeNamespace = targetNamespace2;
        }

        List<DatabaseDeclarativeConfig> allDeclarationsByNamespace = declarativeDbaasCreationService.findAllByNamespace(activeNamespace.getNamespace());
        List<DatabaseRegistry> dbrInActiveNamespace = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(activeNamespace.getNamespace());

        allDeclarationsByNamespace.stream()
                .filter(declarativeConfig -> (VERSION_STATE.equals(declarativeConfig.getVersioningType())))
                .forEach(declaration ->
                        dbrInActiveNamespace.forEach(dbr -> {
                            if (declaration.isDatabaseRegistryBaseOnConfig(dbr)) {
                                dbr.setBgVersion(activeNamespace.getVersion());
                                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveAnyTypeLogDb(dbr);
                            }
                        }));
    }


    public Response createOrUpdateDatabaseWarmup(DatabaseDeclarativeConfig databaseConfig, String version) {
        if (Boolean.TRUE.equals(databaseConfig.getLazy())) {
            return Response.ok().build();
        }

        Response response = executeWithRetry(
                () -> aggregatedDatabaseAdministrationService.createDatabaseFromRequest(
                        new DatabaseCreateRequestV3(databaseConfig, (String) databaseConfig.getClassifier().get("microserviceName"), Role.ADMIN.toString()),
                        (String) databaseConfig.getClassifier().get("namespace"),
                        dBaaService::providePasswordFor,
                        Role.ADMIN.toString(), version),
                r -> r == null || !Response.Status.Family.SUCCESSFUL.equals(r.getStatusInfo().getFamily()),
                ex -> {
                    log.error("Cannot create database with classifier = {}", databaseConfig.getClassifier());
                    throw new RuntimeException("Cannot create database: " + ex.getMessage(), ex);
                }
        );
        if (response == null) {
            log.error("Cannot create database with classifier = {}", databaseConfig.getClassifier());
            throw new RuntimeException("Cannot create database: empty response");
        }
        return Response.status(response.getStatusInfo()).build();
    }

    public NamespaceBackup createDatabaseBackup(UUID backupId, String namespace, UUID databaseId) {
        return executeWithRetry(() -> backupsService.collectBackupSingleDatabase(namespace, backupId, true, databaseId),
                b -> (b == null || NamespaceBackup.Status.FAIL.equals(b.getStatus())),
                ex -> {
                    log.error("Cannot do database backup with id = {}", databaseId);
                    throw new RuntimeException("Cannot do database backup");
                });
    }

    public NamespaceBackupDeletion deleteBackup(UUID namespaceBackupId) {
        return executeWithRetry(() -> {
                    Optional<NamespaceBackup> namespaceBackup = backupsDbaasRepository.findById(namespaceBackupId);
                    return backupsService.deleteBackup(namespaceBackup.orElseThrow());
                },
                b -> (b == null || Status.FAIL.equals(b.getStatus())),
                ex -> {
                    log.error("Cannot do backup deletion with id = {}", namespaceBackupId);
                    throw new RuntimeException("Cannot do backup deletion");
                });
    }

    public Optional<Database> restoreDatabase(DatabaseDeclarativeConfig databaseDeclarativeConfig, String version,
                                              UUID backupId, UUID restorationId, UUID namespaceBackupId, Map<String, String> prefixMap) {
        Optional<DatabaseRegistry> databaseByClassifierAndType = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().getDatabaseByClassifierAndType(databaseDeclarativeConfig.getClassifier(), databaseDeclarativeConfig.getType());
        if (databaseByClassifierAndType.isPresent()) {
            throw new DBCreationConflictException("Database with classifier = " + databaseByClassifierAndType.get().getClassifier() + " to restore already exist");
        }
        NamespaceRestoration r = executeWithRetry(() -> {
                    try {
                        NamespaceBackup backup = backupsService.deleteRestore(namespaceBackupId, restorationId);
                        return backupsService.restore(backup, restorationId, databaseDeclarativeConfig.getNamespace(),
                                true, version, databaseDeclarativeConfig.getClassifier(), prefixMap);
                    } catch (NamespaceRestorationFailedException e) {
                        log.error("database with classifier = {} failed restoration with exception = {}",
                                databaseDeclarativeConfig.getClassifier(), e.getMessage());
                        throw new RuntimeException(e);
                    }
                },
                restore -> (restore == null || Status.FAIL.equals(restore.getStatus())),
                ex -> {
                    URI location = null;
                    try {
                        location = new URI(DBAAS_PATH + "/backups/" + backupId + "/restorations/" + restorationId);
                    } catch (URISyntaxException se) {
                        log.warn("Failed to build URI, error: {}", se.getMessage());
                    }
                    throw new BackupExecutionException(location, String.format("Backup restore %s execution failed", restorationId), ex);
                });

        DatabasesBackup databasesBackup = r.getRestoreResults().get(0).getDatabasesBackup();
        String databaseName = r.getRestoreResults().get(0).getChangedNameDb().get(databasesBackup.getDatabases().get(0));
        return logicalDbDbaasRepository.getDatabaseDbaasRepository().findByNameAndAdapterId(databaseName, databasesBackup.getAdapterId());
    }

    private <T> T executeWithRetry(Supplier<T> action, Predicate<T> condition, Consumer<Throwable> exceptionConsumer) {
        int counter = 0;
        Throwable ex;
        T actionResult = null;
        boolean stopRetrying = false;
        do {
            ex = null;
            try {
                if (QuarkusTransaction.isActive()) {
                    actionResult = QuarkusTransaction.requiringNew().call(() -> action.get());
                } else {
                    actionResult = action.get();
                }
            } catch (Throwable e) {
                log.warn("Some exception happened = {}, during execution with retry", e.getMessage(), e);
                ex = e;
            } finally {
                log.debug("action result = {}", actionResult);
                if (!Thread.currentThread().isInterrupted()) {
                    if (ex != null || actionResult == null || Boolean.TRUE.equals(condition.test(actionResult))) {
                        try {
                            Thread.sleep(5000L + random.nextInt(2048));
                        } catch (InterruptedException exception) {
                            stopRetrying = true;
                        }
                    }
                }
                counter++;
                log.debug("counter = {}", counter);
            }
            if (ex != null && ex.getClass().equals(InteruptedPollingException.class)) {
                log.info("Task was terminated");
                stopRetrying = true;
            }
        } while (!stopRetrying && !Thread.currentThread().isInterrupted() && counter < 5 && Boolean.TRUE.equals(condition.test(actionResult)));
        if (stopRetrying) {
            Thread.currentThread().interrupt();
        }
        if (actionResult == null || Boolean.TRUE.equals(condition.test(actionResult)) || stopRetrying) {
            exceptionConsumer.accept(ex);
        }
        return actionResult;
    }

    public List<BgDomain> getDomains() {
        return bgDomainRepository.findAll().list();
    }

    @Transactional
    public void destroyDomain(Set<String> bgStateRequest) {
        BgDomain bgDomainToDrop = getDomain(bgStateRequest.stream().findFirst().orElseThrow(() -> new BgRequestValidationException("Request doesn't contain any namespace")));
        Set<String> setNamespaces = bgDomainToDrop.getNamespaces().stream().map(BgNamespace::getNamespace).collect(Collectors.toSet());

        if (!bgStateRequest.equals(setNamespaces)) {
            throw new BgRequestValidationException("Request with incorrect namespaces");
        }

        bgDomainToDrop.getNamespaces()
                .forEach(bgNamespace -> {
                    String namespace = bgNamespace.getNamespace();
                    List<DatabaseRegistry> namespaceDatabaseRegistries = logicalDbDbaasRepository
                            .getDatabaseRegistryDbaasRepository().findAnyLogDbRegistryTypeByNamespace(namespace)
                            .stream().filter(Predicate.not(DatabaseRegistry::isMarkedForDrop)).toList();
                    if (!namespaceDatabaseRegistries.isEmpty()) {
                        log.error("namespace = {} still have databases", namespace);
                        throw new BgNamespaceNotEmptyException("namespace = " + namespace + " still have databases");
                    }
                });

        bgDomainRepository.delete(bgDomainToDrop);
        bgDomainToDrop.getNamespaces().forEach(bgNamespace -> bgTrackRepository.deleteAllByNamespace(bgNamespace.getNamespace()));
    }

    public BgDomain getDomain(String namespace) {
        return bgNamespaceRepository.findBgNamespaceByNamespace(namespace)
                .orElseThrow(() -> new BgDomainNotFoundException("BgDomin by namespace =" + namespace + " is not exist")).getBgDomain();
    }

    public Optional<BgDomain> getDomainByControllerNamespace(String namespace) {
        return bgDomainRepository.findByControllerNamespace(namespace);
    }

    @Transactional
    public List<DatabaseRegistry> commit(BgStateRequest bgStateRequest) {
        Optional<BgStateRequest.BGStateNamespace> optionalBgStateNamespaceIdle = bgStateRequest.getBGState().getBgNamespaceWithState(IDLE_STATE);
        BgStateRequest.BGStateNamespace bgStateNamespaceIdle = optionalBgStateNamespaceIdle.orElseThrow();
        Optional<BgStateRequest.BGStateNamespace> optionalBgStateNamespaceActive = bgStateRequest.getBGState().getBgNamespaceWithState(ACTIVE_STATE);
        BgStateRequest.BGStateNamespace bgStateNamespaceActive = optionalBgStateNamespaceActive.orElseThrow();

        BgNamespace idleBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(bgStateNamespaceIdle.getName())
                .orElseThrow(() -> new BgRequestValidationException("Can't find idle namespace for namespace"));
        BgNamespace activeBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(bgStateNamespaceActive.getName())
                .orElseThrow(() -> new BgRequestValidationException("Can't find active namespace for namespace"));
        if (!ACTIVE_STATE.equals(activeBgNamespace.getState()) ||
                !(CANDIDATE_STATE.equals(idleBgNamespace.getState()) || LEGACY_STATE.equals(idleBgNamespace.getState()))) {
            throw new BgRequestValidationException("Only Active + Candidate/Legacy namespaces are allowed for commit");
        }

        List<DatabaseRegistry> markedDatabaseAsOrphan = commitDatabasesByNamespace(bgStateNamespaceIdle.getName());

        deleteNamespaceRules(bgStateNamespaceIdle.getName());
        deleteMicroserviceRules(bgStateNamespaceIdle.getName());
        deleteDatabaseRoles(bgStateNamespaceIdle.getName());

        Optional<BgNamespace> optionalOriginBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(bgStateRequest.getBGState().getOriginNamespace().getName());

        log.debug("Update state for bgNamespace = {} to state = {}", optionalOriginBgNamespace, bgStateRequest.getBGState().getOriginNamespace().getState());
        BgNamespace originBgNamespace = optionalOriginBgNamespace.orElseThrow();
        originBgNamespace.setState(bgStateRequest.getBGState().getOriginNamespace().getState());
        originBgNamespace.setVersion(bgStateRequest.getBGState().getOriginNamespace().getVersion());
        originBgNamespace.setUpdateTime(bgStateRequest.getBGState().getUpdateTime());
        bgNamespaceRepository.persist(originBgNamespace);

        Optional<BgNamespace> optionalPeerBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(bgStateRequest.getBGState().getPeerNamespace().getName());
        log.debug("Update state for bgNamespace = {} to state={}", optionalPeerBgNamespace, bgStateRequest.getBGState().getPeerNamespace().getState());
        BgNamespace peerBgNamespace = optionalPeerBgNamespace.orElseThrow();
        peerBgNamespace.setState(bgStateRequest.getBGState().getPeerNamespace().getState());
        peerBgNamespace.setVersion(bgStateRequest.getBGState().getPeerNamespace().getVersion());
        peerBgNamespace.setUpdateTime(bgStateRequest.getBGState().getUpdateTime());
        bgNamespaceRepository.persist(peerBgNamespace);

        return markedDatabaseAsOrphan;
    }

    @Transactional
    public List<DatabaseRegistry> commitDatabasesByNamespace(String namespace) {
        declarativeDbaasCreationService.deleteDeclarativeConfigurationByNamespace(namespace);

        log.debug("mark versioned databases for drop in namespace = {}", namespace);
        List<DatabaseRegistry> versionedDatabases = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllVersionedDatabaseRegistries(namespace);
        dBaaService.markVersionedDatabasesAsOrphan(versionedDatabases);

        log.debug("mark obsolete transactional databases for drop in namespace = {}", namespace);
        List<DatabaseRegistry> transactionalDatabases = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllTransactionalDatabaseRegistries(namespace)
                .stream().filter(trDb -> trDb.getDatabaseRegistry().size() == 1).toList();
        transactionalDatabases.forEach(dBaaService::markDatabasesAsOrphan);

        List<DatabaseRegistry> databasesForDrop = new ArrayList<>(versionedDatabases);
        databasesForDrop.addAll(transactionalDatabases);
        dBaaService.dropDatabasesAsync(namespace, databasesForDrop);
        return versionedDatabases;
    }

    private void deleteDatabaseRoles(String namespace) {
        databaseRolesService.removeDatabaseRole(namespace);
    }

    private void deleteNamespaceRules(String namespace) {
        balancingRulesService.removeRulesByNamespace(namespace);
    }

    private void deleteMicroserviceRules(String namespace) {
        balancingRulesService.removePerMicroserviceRulesByNamespace(namespace);
    }

    public TaskState getGeneralStatus(ProcessInstanceImpl process) {
        TaskState generalStatus = TaskState.NOT_STARTED;
        if (process.getTasks().stream().anyMatch(t -> TaskState.IN_PROGRESS.equals(t.getState()))) {
            generalStatus = TaskState.IN_PROGRESS;
        }
        if (process.getTasks().stream().anyMatch(t -> TaskState.FAILED.equals(t.getState()))) {
            generalStatus = TaskState.FAILED;
        }

        if (process.getTasks().stream().allMatch(t -> TaskState.COMPLETED.equals(t.getState()))) {
            generalStatus = TaskState.COMPLETED;
        }
        return generalStatus;
    }


    @Transactional
    public void updateWarmupBgNamespace(String candidateNamespace, String targetVersion) {
        Optional<BgDomain> bgDomainContainsOpt = getBgDomainContains(candidateNamespace);
        BgDomain bgDomain = bgDomainContainsOpt.orElseThrow();
        BgNamespace bgNamespaceCandidate = bgDomain.getNamespaces().stream().filter(bgNamespace
                -> bgNamespace.getNamespace().equals(candidateNamespace)).findFirst().orElseThrow();
        BgNamespace bgNamespaceActive = bgDomain.getNamespaces().stream().filter(bgNamespace
                -> !bgNamespace.getNamespace().equals(candidateNamespace)).findFirst().orElseThrow();
        updateBgNamespace(bgNamespaceActive, ACTIVE_STATE, bgNamespaceActive.getVersion());
        updateBgNamespace(bgNamespaceCandidate, CANDIDATE_STATE, targetVersion);
    }

    public void promote(BgStateRequest bgStateRequest) {
        updateBgDomainByBgStateRequest(bgStateRequest, ACTIVE_STATE, CANDIDATE_STATE, "promote");
    }

    public void rollback(BgStateRequest bgStateRequest) {
        updateBgDomainByBgStateRequest(bgStateRequest, ACTIVE_STATE, LEGACY_STATE, "rollback");
    }

    private void updateBgDomainByBgStateRequest(BgStateRequest bgStateRequest, String firstAllowedState, String secondAllowedState, String operationName) {
        BgStateRequest.BGStateNamespace bgRequestedOriginNamespace = bgStateRequest.getBGState().getOriginNamespace();
        BgStateRequest.BGStateNamespace bgRequestedPeerNamespace = bgStateRequest.getBGState().getPeerNamespace();

        Optional<BgNamespace> foundedOriginBgNamespaceOptional = bgNamespaceRepository.findBgNamespaceByNamespace(bgRequestedOriginNamespace.getName());
        Optional<BgNamespace> foundedPeerBgNamespaceOptional = bgNamespaceRepository.findBgNamespaceByNamespace(bgRequestedPeerNamespace.getName());
        BgNamespace foundedOriginBgNamespace = foundedOriginBgNamespaceOptional.orElseThrow();
        BgNamespace foundedPeerBgNamespace = foundedPeerBgNamespaceOptional.orElseThrow();

        String firstState = foundedOriginBgNamespace.getState();
        String secondState = foundedPeerBgNamespace.getState();
        if (!((Objects.equals(firstAllowedState, firstState) && Objects.equals(secondAllowedState, secondState)) ||
                (Objects.equals(secondAllowedState, firstState) && Objects.equals(firstAllowedState, secondState)))) {
            throw new BgRequestValidationException("Only " + firstAllowedState + " + " + secondAllowedState + " namespaces are allowed for " + operationName);
        }

        foundedOriginBgNamespace.setState(bgRequestedOriginNamespace.getState());
        bgNamespaceRepository.persist(foundedOriginBgNamespace);

        foundedPeerBgNamespace.setState(bgRequestedPeerNamespace.getState());
        bgNamespaceRepository.persist(foundedPeerBgNamespace);
    }

    public List<DatabaseRegistry> dropOrphanDatabases(List<String> namespaces, Boolean delete) {
        List<DatabaseRegistry> orphanDatabases = getOrphanDatabases(namespaces);
        if (delete) {
            log.info("{} databases are going to be deleted", orphanDatabases.size());
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            executorService.submit(() -> {
                log.info("Start async dropping orphan databases");
                dBaaService.dropDatabases(orphanDatabases, null);
            });
            executorService.shutdown();

        }
        return orphanDatabases;
    }

    public List<DatabaseRegistry> getOrphanDatabases(List<String> namespaces) {
        List<Database> orphanDatabases = logicalDbDbaasRepository.getDatabaseDbaasRepository().findByDbState(DbState.DatabaseStateStatus.ORPHAN);
        Set<DatabaseRegistry> orphanDbsRegistries = new HashSet<>();
        orphanDatabases.forEach(db -> {
            orphanDbsRegistries.addAll(db.getDatabaseRegistry());
        });
        return orphanDbsRegistries.stream().filter(dbr -> namespaces.contains(dbr.getNamespace())).collect(Collectors.toList());
    }
}
