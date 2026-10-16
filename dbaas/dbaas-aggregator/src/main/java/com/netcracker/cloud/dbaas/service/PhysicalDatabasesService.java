package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Maps;
import com.netcracker.cloud.dbaas.dto.EnsuredUser;
import com.netcracker.cloud.dbaas.dto.PhysicalDatabaseRegistrationBuilder;
import com.netcracker.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.*;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.exceptions.*;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.PhysicalDatabaseInstructionRepository;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.jodah.failsafe.Failsafe;
import org.apache.commons.collections4.CollectionUtils;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.netcracker.cloud.dbaas.DbaasApiPath.DBAAS_PATH_V3;
import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;
import static com.netcracker.cloud.dbaas.service.InstructionService.*;
import static com.netcracker.cloud.dbaas.service.LockService.getLockConfiguration;
import static com.netcracker.cloud.dbaas.utils.RetryPolicyUtil.buildRetryPolicy;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

@ApplicationScoped
@Slf4j
public class PhysicalDatabasesService {
    private final static String MESSAGE_UPDATING_EXISTING_DATABASE = "Updating existing database with phydbid = {}, adapterAddress = {}";
    private final static String MESSAGE_DATABASE_UPDATED = "Database updated";

    private final int RETRY_ATTEMPT = 2;
    private final Duration RETRY_DELAY = Duration.of(3, TimeUnit.SECONDS.toChronoUnit());

    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @Inject
    PhysicalDatabaseRegistrationHandshakeClient handshakeClient;
    @Inject
    PasswordEncryption encryption;
    @Inject
    DbaasAdapterRESTClientFactory dbaasAdapterRESTClientFactory;
    @Inject
    LogicalDbDbaasRepository logicalDbDbaasRepository;
    @Inject
    AdapterActionTrackerClient tracker;
    @Inject
    InstructionService instructionService;
    @Inject
    LockService lockService;
    @Inject
    AsyncOperations asyncOperations;
    @Inject
    PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;

    private Map<String, DbaasAdapter> startedAdaptersCache = new ConcurrentHashMap<>();
    private Map<String, PhysicalDatabase> physicalDatabaseCache = new ConcurrentHashMap<>();

    @PostConstruct
    void validateAdaptersApiVersions() {
        getAllRegisteredDatabases().stream()
                .map(PhysicalDatabase::getAdapter)
                .filter(adapter -> adapter.getApiVersions() == null || adapter.getApiVersions().getSpecs().isEmpty())
                .map(ExternalAdapterRegistrationEntry::getAddress)
                .reduce((acc, host) -> acc + ", " + host)
                .ifPresent(s -> log.warn("The following list of adapters does not support the latest contract, so some functionality will work in a limited mode: {}. " +
                        "Please update the physical databases according to the compatibility matrix (see Installation Notes). " +
                        "Compatibility with the current adapter API will be removed in the DBaaS 25.1 release.", s));
    }

    public List<PhysicalDatabase> getAllRegisteredDatabases() {
        return physicalDatabaseDbaasRepository.findAll();
    }

    public List<PhysicalDatabase> getRegisteredDatabases(String type) {
        return physicalDatabaseDbaasRepository.findByType(type).collect(Collectors.toList());
    }

    @Nullable
    public PhysicalDatabase getByPhysicalDatabaseIdentifier(String physicalDatabaseIdentifier) {
        return physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseIdentifier);
    }

    public List<PhysicalDatabase> getPhysicalDatabaseContainsLabel(String key, String value, String type) {
        Stream<PhysicalDatabase> resultDatabases = physicalDatabaseDbaasRepository.findByType(type);
        return resultDatabases
                .filter(o -> o.getLabels() != null
                        && o.getLabels().containsKey(key) && o.getLabels().get(key).equals(value))
                .collect(Collectors.toList());
    }


    // returns false if new db was registered, true if update
    @Transactional
    public boolean save(String physicalDatabaseId,
                        String type,
                        PhysicalDatabaseRegistryRequestV3 request) throws
            AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        log.info("Starting registration of {} with type {}", physicalDatabaseId, type);
        String username = request.getHttpBasicCredentials().getUsername();
        String password = request.getHttpBasicCredentials().getPassword();
        Optional<PhysicalDatabase> foundDatabase = findAndValidate(
                physicalDatabaseId,
                type,
                request,
                username,
                password);
        if (foundDatabase.isPresent()) {
            writeChanges(physicalDatabaseId, request, foundDatabase.get());
            return true;
        }
        log.info("Registration of new database");
        encryption.encryptPassword(request);
        PhysicalDatabaseRegistrationBuilder builder = new PhysicalDatabaseRegistrationBuilder();
        boolean isThisANewPhyDBOfThisType = physicalDatabaseDbaasRepository.findByType(type).count() == 0;
        PhysicalDatabase databaseRegistration =
                builder.addPhysicalDatabaseIdentifier(physicalDatabaseId)
                        .addType(type)
                        .addAdapter(UUID.randomUUID().toString(), request.getAdapterAddress(), request.getHttpBasicCredentials(), VERSION_2, request.getMetadata().getApiVersions())
                        .addLabels(request.getLabels())
                        .global(isThisANewPhyDBOfThisType)
                        .addUnidentified(false)
                        .addRoles(Collections.singletonList(Role.ADMIN.toString()))
                        .addRoHost(request.getMetadata().getRoHost())
                        .build();
        physicalDatabaseDbaasRepository.save(databaseRegistration);
        log.info("Database created");
        return false;
    }

    public Optional<PhysicalDatabase> foundPhysicalDatabase(String physicalDatabaseId,
                                                            String type,
                                                            PhysicalDatabaseRegistryRequestV3 request) throws
            AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        log.info("Starting registration of {} with type {}", physicalDatabaseId, type);
        String username = request.getHttpBasicCredentials().getUsername();
        String password = request.getHttpBasicCredentials().getPassword();
        return findAndValidateV3(
                physicalDatabaseId,
                type,
                request,
                username,
                password);
    }

    @Transactional
    public PhysicalDatabase physicalDatabaseRegistration(String physicalDatabaseId,
                                                         String type,
                                                         PhysicalDatabaseRegistryRequestV3 request) {
        log.info("Registration of new database");
        request.getMetadata().getSupportedRoles().replaceAll(String::toLowerCase);
        encryption.encryptPassword(request);
        PhysicalDatabaseRegistrationBuilder builder = new PhysicalDatabaseRegistrationBuilder();
        boolean isThisANewPhyDBOfThisType = physicalDatabaseDbaasRepository.findByType(type).count() == 0;
        PhysicalDatabase databaseRegistration =
                builder.addPhysicalDatabaseIdentifier(physicalDatabaseId)
                        .addType(type)
                        .addAdapter(UUID.randomUUID().toString(),
                                request.getAdapterAddress(),
                                request.getHttpBasicCredentials(),
                                request.getMetadata().getApiVersion(),
                                request.getMetadata().getApiVersions())
                        .addLabels(request.getLabels())
                        .global(isThisANewPhyDBOfThisType)
                        .addUnidentified(false)
                        .addRoles(request.getMetadata().getSupportedRoles())
                        .addFeatures(request.getMetadata().getFeatures())
                        .addRoHost(request.getMetadata().getRoHost())
                        .build();
        PhysicalDatabase save = physicalDatabaseDbaasRepository.save(databaseRegistration);
        log.info("Database created");
        return save;
    }

    public void writeChanges(String physicalDatabaseId,
                             PhysicalDatabaseRegistryRequestV3 updateReq,
                             PhysicalDatabase existing) {
        log.info(MESSAGE_UPDATING_EXISTING_DATABASE,
                existing.getPhysicalDatabaseIdentifier(), existing.getAdapter().getAddress());
        existing.setLabels(updateReq.getLabels());
        encryption.deletePassword(existing);
        encryption.encryptPassword(updateReq);
        existing.getAdapter().setHttpBasicCredentials(updateReq.getHttpBasicCredentials());
        existing.setRoles(updateReq.getMetadata().getSupportedRoles());
        existing.getAdapter().setSupportedVersion(updateReq.getMetadata().getApiVersion());
        existing.getAdapter().setAddress(updateReq.getAdapterAddress());
        existing.getAdapter().setApiVersions(updateReq.getMetadata().getApiVersions());
        existing.setFeatures(updateReq.getMetadata().getFeatures());
        existing.setRoHost(updateReq.getMetadata().getRoHost());
        if (existing.isUnidentified()) {
            existing.setPhysicalDatabaseIdentifier(physicalDatabaseId);
            existing.setUnidentified(false);
        }
        physicalDatabaseDbaasRepository.save(existing);
        // reset cache so restemplate with new credentials is created
        resetCacheForAdapter(existing.getAdapter().getAdapterId());
        resetCacheForPhyDb(existing.getPhysicalDatabaseIdentifier());
        log.info(MESSAGE_DATABASE_UPDATED);
    }

    public List<Database> getDatabasesByPhysDbAndType(String phydbid, String type) {
        String adapterId = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(phydbid).getAdapter().getAdapterId();
        return logicalDbDbaasRepository.getDatabaseDbaasRepository().findDatabasesByAdapterIdAndType(adapterId, type, false);
    }

    public List<PhysicalDatabase> getPhysicalDatabaseByAdapterHost(String adapterHost) {
        return physicalDatabaseDbaasRepository.findByAdapterHost(adapterHost);
    }

    public void savePhysicalDatabaseWithRoles(String physicalDatabaseId, PhysicalDatabaseRegistryRequestV3 updateReq) {
        PhysicalDatabase existing = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        writeChanges(physicalDatabaseId, updateReq, existing);
    }

    public PhysicalDatabase balanceByType(String type) {
        return firstGlobalFor(type);
    }

    private PhysicalDatabase firstGlobalFor(String type) { // assuming there is only one default for type
        return physicalDatabaseDbaasRepository.findByType(type).filter(PhysicalDatabase::isGlobal)
                .findFirst().orElseThrow(
                        () -> new UnregisteredPhysicalDatabaseException("DB type: " + type));
        //TODO add balancing here in case if there are more defaults
    }

    // Returns Optional.empty() if new database should be created or PhysicalDatabaseRegistration
    // if already registered db should be updated
    private Optional<PhysicalDatabase> findAndValidate(
            String physicalDatabaseId,
            String type,
            PhysicalDatabaseRegistryRequestV3 request,
            String username, String password)
            throws
            AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        handshakeClient.handshake(physicalDatabaseId, request.getAdapterAddress(), type, username, password, VERSION_2);
        PhysicalDatabase byPhyDBId = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        PhysicalDatabase byAdapter = physicalDatabaseDbaasRepository.findByAdapterAddress(request.getAdapterAddress());
        if (byPhyDBId == null && byAdapter == null) {
            log.info("Database with set params does not exist, new one should be registered");
            return Optional.empty();
        }
        if (byPhyDBId != null && !request.getAdapterAddress().equals(byPhyDBId.getAdapter().getAddress())) {
            if (!isTLSUpdate(byPhyDBId.getAdapter().getAddress(), request.getAdapterAddress())) {
                log.error("Address from request: {} conflicts with already saved address: {}", request.getAdapterAddress(), byPhyDBId.getAdapter().getAddress());
                throw new PhysicalDatabaseRegistrationConflictException("Database with phydbid " + physicalDatabaseId +
                        " already exists, but adapter address differs. Cannot register another one as adapter " +
                        "address is unique and immutable");
            }
        } else if (byAdapter != null && !physicalDatabaseId.equals(byAdapter.getPhysicalDatabaseIdentifier()) &&
                byAdapter.isIdentified()) {
            log.error("sent physicalDatabaseIdentifier = {}, stored at db = {}",
                    physicalDatabaseId, byAdapter.getPhysicalDatabaseIdentifier());
            throw new PhysicalDatabaseRegistrationConflictException("Database with set adapter exists but its " +
                    "phydbid is different from provided value and cannot be updated");
        }
        return Optional.of(byPhyDBId != null ? byPhyDBId : byAdapter);
    }

    private boolean isTLSUpdate(String oldAddress, String newAddress) {
        try {
            URL oldURI = new URL(oldAddress);
            URL newURI = new URL(newAddress);
            boolean hostsAreEqual = oldURI.getHost().equals(newURI.getHost());
            boolean protocolsAreEqual = oldURI.getProtocol().equals(newURI.getProtocol());
            return hostsAreEqual && !protocolsAreEqual;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private Optional<PhysicalDatabase> findAndValidateV3(
            String physicalDatabaseId,
            String type,
            PhysicalDatabaseRegistryRequestV3 request,
            String username, String password)
            throws
            AdapterUnavailableException,
            PhysicalDatabaseRegistrationConflictException {
        if (request.getStatus().equals("run")) {
            handshakeClient.handshake(physicalDatabaseId, request.getAdapterAddress(), type, username, password, VERSION_2);
        }
        PhysicalDatabase byPhyDBId = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDatabaseId);
        PhysicalDatabase byAdapter = physicalDatabaseDbaasRepository.findByAdapterAddress(request.getAdapterAddress());
        if (byPhyDBId == null && byAdapter == null) {
            log.info("Database with set params does not exist, new one should be registered");
            return Optional.empty();
        }
        if (byPhyDBId != null && !request.getAdapterAddress().equals(byPhyDBId.getAdapter().getAddress())) {
            if (!isTLSUpdate(byPhyDBId.getAdapter().getAddress(), request.getAdapterAddress())) {
                log.error("Address from request: {} conflicts with already saved address: {}", request.getAdapterAddress(), byPhyDBId.getAdapter().getAddress());
                throw new PhysicalDatabaseRegistrationConflictException("Database with physical DB id " + physicalDatabaseId +
                        " already exists, but adapter address differs. Cannot register another one as adapter " +
                        "address is unique and immutable");
            }
        } else if (byAdapter != null && !physicalDatabaseId.equals(byAdapter.getPhysicalDatabaseIdentifier()) &&
                byAdapter.isIdentified()) {
            log.error("sent physicalDatabaseIdentifier = {}, stored at db = {}",
                    physicalDatabaseId, byAdapter.getPhysicalDatabaseIdentifier());
            throw new PhysicalDatabaseRegistrationConflictException("Database with set adapter exists but its " +
                    "physical DB id is different from provided value and cannot be updated");
        }
        return Optional.of(byPhyDBId != null ? byPhyDBId : byAdapter);
    }

    public List<DbaasAdapter> getAllAdapters() { // this action would effectively initialize all clients to registered adapters
        return physicalDatabaseDbaasRepository.findAll().stream()
                .map(PhysicalDatabase::getAdapter)
                .map(ExternalAdapterRegistrationEntry::getAdapterId).map(this::getAdapterById)
                .collect(Collectors.toList());
    }

    public DbaasAdapter getAdapterById(String adapterId) {
        return startedAdaptersCache.computeIfAbsent(adapterId, adapter -> startAdapter(adapterId));
    }

    private void resetCacheForAdapter(String adapterId) {
        startedAdaptersCache.computeIfPresent(adapterId, (id, dbaasAdapter) -> startAdapter(id));
    }

    private void resetCacheForPhyDb(String phyDbId) {
        physicalDatabaseCache.computeIfPresent(phyDbId, (id, physicalDatabase) -> getByPhysicalDatabaseIdentifier(phyDbId));
    }

    public PhysicalDatabase searchInPhysicalDatabaseCache(String phyDbId) {
        return Optional.ofNullable(physicalDatabaseCache.computeIfAbsent(phyDbId, id -> getByPhysicalDatabaseIdentifier(phyDbId)))
                .orElseThrow(() -> new NotFoundException(String.format("Physical Database with phyDbId %s not found", phyDbId)));
    }


    public DbaasAdapter getAdapterByPhysDbId(String physDbId) {
        PhysicalDatabase physicalDatabase = getByPhysicalDatabaseIdentifier(physDbId);
        if (physicalDatabase == null) {
            throw new UnregisteredPhysicalDatabaseException("Identifier: " + physDbId);
        }
        return getAdapterById(physicalDatabase.getAdapter().getAdapterId());
    }

    public PhysicalDatabase getByAdapterId(String adapterId) {
        return physicalDatabaseDbaasRepository.findByAdapterId(adapterId);
    }

    private DbaasAdapter startAdapter(String adapterId) {
        PhysicalDatabase physicalDatabase = physicalDatabaseDbaasRepository.findByAdapterId(adapterId);
        if (physicalDatabase == null) {
            throw new UnregisteredPhysicalDatabaseException("Adapter identifier: " + adapterId);
        }
        DbaasAdapter dbaasAdapter = getDbaasAdapterRESTClient(physicalDatabase);
        log.info("adapter successfully registered");
        return dbaasAdapter;
    }

    private DbaasAdapter getDbaasAdapterRESTClient(PhysicalDatabase physicalDatabase) {
        log.info("Starting registration of adapter with id = {}", physicalDatabase.getAdapter().getAdapterId());
        String username = physicalDatabase.getAdapter().getHttpBasicCredentials().getUsername();
        String password = encryption.decrypt(physicalDatabase.getAdapter().getHttpBasicCredentials().getPassword());
        if (physicalDatabase.getAdapter().getSupportedVersion().equals(VERSION_2)) {
            return dbaasAdapterRESTClientFactory.createDbaasAdapterClientV2(username, password, physicalDatabase.getAdapter().getAddress(),
                    physicalDatabase.getType(), physicalDatabase.getAdapter().getAdapterId(), tracker, physicalDatabase.getAdapter().getApiVersions());
        } else {
            return dbaasAdapterRESTClientFactory.createDbaasAdapterClient(username, password, physicalDatabase.getAdapter().getAddress(),
                    physicalDatabase.getType(), physicalDatabase.getAdapter().getAdapterId(), tracker);
        }
    }

    public RegisteredPhysicalDatabasesDTO presentPhysicalDatabases(List<PhysicalDatabase> source) {
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identifiedMap = Maps.newHashMap();
        RegisteredPhysicalDatabasesDTO getRegisteredResponse = new RegisteredPhysicalDatabasesDTO();
        for (PhysicalDatabase entity : source) {
            ExternalAdapterRegistrationEntry adapterRegEntry = entity.getAdapter();
            DbaasAdapter adapter = getAdapterById(adapterRegEntry.getAdapterId());
            PhysicalDatabaseRegistrationResponseDTOV3 dto = new PhysicalDatabaseRegistrationResponseDTOV3();
            dto.setSupportedVersion(entity.getAdapter().getSupportedVersion());
            dto.setType(entity.getType());
            dto.setLabels(entity.getLabels() == null ? Collections.emptyMap() : entity.getLabels());
            dto.setAdapterId(adapterRegEntry.getAdapterId());
            dto.setAdapterAddress(adapterRegEntry.getAddress());
            dto.setSupportedRoles(entity.getRoles());
            dto.setGlobal(entity.isGlobal());
            try {
                final Map<String, Boolean> supports = adapter.supports();
                dto.setSupports(supports);
            } catch (Exception e) {
                log.error("Failed to retrieve supports info from adapter: {}, type: {}, err: {}",
                        adapter.identifier(), adapter.type(), e.getMessage());
            }
            dto.setFeatures(entity.getFeatures());
            identifiedMap.put(entity.getPhysicalDatabaseIdentifier(), dto);
        }
        getRegisteredResponse.setIdentified(identifiedMap);
        return getRegisteredResponse;
    }

    public boolean checkContainsConnectedLogicalDb(PhysicalDatabase database) {
        String adapterId = database.getAdapter().getAdapterId();
        log.debug("Start check if adapter {} contains any logical databases", adapterId);

        List<String> registeredInAdapter = logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().findAllInternalDatabases().stream()
                .filter(db -> adapterId.equals(db.getAdapterId()))
                .filter(db -> !db.isMarkedForDrop())
                .map(DatabaseRegistry::getName)
                .toList();
        return !registeredInAdapter.isEmpty();
    }

    public Optional<PhysicalDatabase> getPhysicalDatabaseByLogicalDb(Database database) {
        if (database.getAdapterId() != null) {
            return Optional.ofNullable(getByAdapterId(database.getAdapterId()));
        }
        if (database.getPhysicalDatabaseId() != null) {
            return Optional.ofNullable(getByPhysicalDatabaseIdentifier(database.getPhysicalDatabaseId()));
        }
        return Optional.empty();
    }

    @Transactional
    public void dropDatabase(PhysicalDatabase databaseForDeletion) {
        physicalDatabaseDbaasRepository.delete(databaseForDeletion);
        log.info("Successfully drop database {}", databaseForDeletion.getPhysicalDatabaseIdentifier());
    }

    public boolean checkSupportedVersion(String physicalDatabaseIdentifier, double version) {
        PhysicalDatabase phydb = getByPhysicalDatabaseIdentifier(physicalDatabaseIdentifier);
        assert phydb != null;
        ApiVersion apiVersions = phydb.getAdapter().getApiVersions();
        if (apiVersions != null && !apiVersions.getSpecs().isEmpty()) {
            for (ApiVersion.Spec spec : apiVersions.getSpecs()) {
                if (spec.getSpecRootUrl().equals("/api")) {
                    double supportedVersion = Double.parseDouble(spec.getMajor() + "." + spec.getMinor());
                    int major = (int) Math.floor(version);
                    return (supportedVersion >= version) && spec.getSupportedMajors().contains(major);
                }
            }
        }
        return false;
    }

    @Transactional
    public void makeGlobal(PhysicalDatabase newGlobal) {
        if (newGlobal.isGlobal()) {
            log.info("Database {} already marked as global", newGlobal.getPhysicalDatabaseIdentifier());
            return;
        }

        Optional<PhysicalDatabase> currentGlobalByType = physicalDatabaseDbaasRepository.findGlobalByType(newGlobal.getType());
        if (currentGlobalByType.isPresent()) {
            PhysicalDatabase currentGlobal = currentGlobalByType.get();
            currentGlobal.setGlobal(false);
            log.info("Make database {} not global", currentGlobal.getPhysicalDatabaseIdentifier());
            physicalDatabaseDbaasRepository.save(currentGlobal);
        }

        newGlobal.setGlobal(true);
        log.info("Make database {} global", newGlobal.getPhysicalDatabaseIdentifier());
        physicalDatabaseDbaasRepository.save(newGlobal);
    }

    public boolean isDbActual(PhysicalDatabaseRegistryRequestV3 registerRequest, PhysicalDatabase existingDatabase) {
        ExternalAdapterRegistrationEntry adapter = existingDatabase.getAdapter();
        Metadata metadata = registerRequest.getMetadata();
        return Objects.equals(registerRequest.getAdapterAddress(), adapter.getAddress())
                && Objects.equals(registerRequest.getHttpBasicCredentials().getUsername(), adapter.getHttpBasicCredentials().getUsername())
                && Objects.equals(registerRequest.getHttpBasicCredentials().getPassword(), encryption.decrypt(adapter.getHttpBasicCredentials().getPassword()))
                && Objects.equals(registerRequest.getLabels(), existingDatabase.getLabels())
                && Objects.equals(metadata.getApiVersion(), adapter.getSupportedVersion())
                && Objects.equals(metadata.getApiVersions(), adapter.getApiVersions())
                && CollectionUtils.isEqualCollection(metadata.getSupportedRoles(), existingDatabase.getRoles())
                && Objects.equals(metadata.getFeatures(), existingDatabase.getFeatures())
                && Objects.equals(metadata.getRoHost(), existingDatabase.getRoHost());
    }

    public Response registerPhysicalDatabase(
            PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase,
            String type,
            String physicalDatabaseId,
            boolean internalMigration
    ) throws JsonProcessingException {
        validateRequest(requestedPhysicalDatabase);

        Optional<PhysicalDatabase> foundDatabase =
                foundPhysicalDatabase(physicalDatabaseId, type, requestedPhysicalDatabase);

        if (foundDatabase.isEmpty()) {
            physicalDatabaseRegistration(physicalDatabaseId, type, requestedPhysicalDatabase);
            return Response.created(
                    URI.create(DBAAS_PATH_V3 + "/" + type + "/physical_databases/" + physicalDatabaseId)
            ).build();
        }

        PhysicalDatabase physicalDatabase = foundDatabase.get();

        if (rolesDifferentAndMultiUsersEnabled(physicalDatabase, requestedPhysicalDatabase)) {
            Response migrationResponse = migrateIfRequired(
                    physicalDatabase,
                    requestedPhysicalDatabase,
                    type,
                    physicalDatabaseId,
                    internalMigration
            );

            if (migrationResponse != null) {
                return migrationResponse;
            }
        }

        if (isDbActual(requestedPhysicalDatabase, physicalDatabase)) {
            log.info("Adapter '{}' update is not required", physicalDatabase.getPhysicalDatabaseIdentifier());
        } else {
            writeChanges(physicalDatabaseId, requestedPhysicalDatabase, physicalDatabase);
        }

        return Response.ok().build();
    }

    private Response migrateIfRequired(
            PhysicalDatabase physicalDatabase,
            PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase,
            String type,
            String physicalDatabaseId,
            boolean internalMigration
    ) throws JsonProcessingException {
        List<Database> logicalDatabases = getLogicalDatabasesForMigration(physicalDatabaseId,
                type,
                requestedPhysicalDatabase.getMetadata().getSupportedRoles());

        if (logicalDatabases.isEmpty()) {
            return null;
        }

        if (!statusRunningAndApiVersionV2(requestedPhysicalDatabase)) {
            log.warn("Adapter status run or adapter version is not equals v2");
            return Response.status(BAD_REQUEST)
                    .entity("Adapter status run or adapter version is not equals v2")
                    .build();
        }

        if (internalMigration) {
            return startInternalMigration(physicalDatabase, logicalDatabases, requestedPhysicalDatabase);
        }
        return startExternalMigration(physicalDatabase, logicalDatabases, requestedPhysicalDatabase);
    }

    private Response startInternalMigration(
            PhysicalDatabase physicalDatabase,
            List<Database> logicalDatabases,
            PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase
    ) throws JsonProcessingException {
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        SimpleLock lock = lockPhysicalDatabaseRegistration(physicalDatabaseId);

        if (lock != null) {
            PhysicalDatabaseInstruction existingInstruction =
                    instructionService.findInstructionByPhyDbId(physicalDatabaseId);

            if (existingInstruction != null && existingInstruction.getFailure() != null) {
                instructionService.deleteInstruction(existingInstruction.getId().toString());
                lockService.unlock(lock);
                return Response.serverError().entity(existingInstruction.getFailure()).build();
            }

            if (existingInstruction == null) {
                createInstruction(physicalDatabase, logicalDatabases, requestedPhysicalDatabase);
            }

            startAsyncInternalMigration(physicalDatabaseId, lock);
        }

        return Response.accepted().build();
    }

    private Response startExternalMigration(
            PhysicalDatabase physicalDatabase,
            List<Database> logicalDatabases,
            PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase
    ) throws JsonProcessingException {
        String physicalDatabaseId = physicalDatabase.getPhysicalDatabaseIdentifier();
        PhysicalDatabaseInstruction existingInstruction = instructionService.findInstructionByPhyDbId(physicalDatabaseId);

        if (existingInstruction == null) {
            Map<String, Object> instruction =
                    createInstruction(physicalDatabase, logicalDatabases, requestedPhysicalDatabase);

            log.info("Sending instruction to create users for roles = {}", requestedPhysicalDatabase.getMetadata().getSupportedRoles());
            return Response
                    .accepted(instruction)
                    .build();
        }

        Instruction instruction = instructionService.findPortion(existingInstruction.getId().toString());

        if (instruction != null) {
            log.info("Got instruction with portion = {}", instruction);
            return Response.accepted(Map.of("instruction", instruction)).build();
        }

        log.warn("Instruction is null, Creating new instruction");

        Instruction recreatedInstruction =
                recreateInstruction(existingInstruction, logicalDatabases);

        return Response.accepted(
                instructionService.saveInstruction(
                        physicalDatabase,
                        recreatedInstruction,
                        requestedPhysicalDatabase
                )
        ).build();
    }

    public void startAsyncInternalMigration(String physicalDatabaseId, SimpleLock simpleLock) {
        PhysicalDatabaseInstruction instruction = physicalDatabaseInstructionRepository
                .findByPhysicalDatabaseId(physicalDatabaseId);

        CompletableFuture.runAsync(asyncOperations.wrapWithContext(() -> {
                            try {
                                asyncProcessInstruction(instruction);
                            } catch (JsonProcessingException e) {
                                log.warn("Exception occurred during parsing json, will be saved in instruction", e);
                                physicalDatabaseInstructionRepository.save(instruction);
                            }
                        }),
                        asyncOperations.getCoordinatorExecutor())
                .whenComplete((_, t) -> {
                            try {
                                if (t != null) {
                                    log.warn("Exception thrown while internal migration process", t);
                                    instruction.setFailure(new FailureRegistrationV3("", t.getCause().getMessage()));
                                    physicalDatabaseInstructionRepository.save(instruction);
                                }
                            } finally {
                                lockService.unlock(simpleLock);
                            }
                        }
                );
    }

    private void asyncProcessInstruction(PhysicalDatabaseInstruction instruction) throws JsonProcessingException {
        List<String> requiredRoles = instruction.getPhysicalDbRegRequest().getMetadata().getSupportedRoles();
        List<List<AdditionalRoles>> partitionedAdditionalRoles = divideAdditionalRoles(convertStringToList(instruction.getContext()));

        DbaasAdapter adapter = Failsafe.with(buildRetryPolicy(RETRY_ATTEMPT, RETRY_DELAY))
                .get(() -> getAdapterByPhysDbId(instruction.getPhysicalDatabaseId()));

        List<CompletableFuture<Void>> futures = partitionedAdditionalRoles.stream()
                .map(portion ->
                        CompletableFuture.supplyAsync(
                                asyncOperations.wrapWithContext(() ->
                                        runLogicalDatabaseMigration(adapter, portion, requiredRoles)
                                ),
                                asyncOperations.getMigrationExecutor()
                        ).thenAccept(instructionRequest -> {
                            updateConnectionAndInstruction(instructionRequest, instruction.getPhysicalDatabaseId());
                        })
                ).toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private List<List<AdditionalRoles>> divideAdditionalRoles(List<AdditionalRoles> additionalRoles) {
        List<List<AdditionalRoles>> portions = new ArrayList<>();
        List<AdditionalRoles> portion = new ArrayList<>(PORTION_SIZE);

        for (AdditionalRoles role : additionalRoles) {
            portion.add(role);

            if (portion.size() == PORTION_SIZE) {
                portions.add(portion);
                portion = new ArrayList<>(PORTION_SIZE);
            }
        }

        if (!portion.isEmpty()) {
            portions.add(portion);
        }

        return portions;
    }

    private InstructionRequestV3 runLogicalDatabaseMigration(
            DbaasAdapter adapter,
            List<AdditionalRoles> additionalRoles,
            List<String> requiredRoles
    ) {
        InstructionRequestV3 instructionRequestV3 = new InstructionRequestV3();
        List<SuccessRegistrationV3> successRegistrationV3s = new ArrayList<>();
        FailureRegistrationV3 failureRegistrationV3 = null;

        for (AdditionalRoles additionalRole : additionalRoles) {
            List<Map<String, Object>> connectionProperties = new ArrayList<>();
            List<DbResource> resources = new ArrayList<>();
            List<String> existRoles = additionalRole.getConnectionProperties().stream().map(cp -> (String) cp.get("role")).toList();
            List<String> missedRoles = getMissedRoles(requiredRoles, existRoles);
            List<EnsuredUser> ensuredUsers;

            try {
                ensuredUsers = ensureUsers(adapter, additionalRole, missedRoles);
            } catch (Exception e) {
                log.warn("Got exception during ensure user, migration will be stopped.", e);
                failureRegistrationV3 = new FailureRegistrationV3();
                failureRegistrationV3.setId(additionalRole.getId().toString());
                failureRegistrationV3.setMessage(e.getMessage());
                break;
            }

            for (EnsuredUser ensuredUser : ensuredUsers) {
                connectionProperties.add(ensuredUser.getConnectionProperties());
                resources.addAll(ensuredUser.getResources());
            }

            successRegistrationV3s.add(new SuccessRegistrationV3(
                            additionalRole.getId(),
                            connectionProperties,
                            resources
                    )
            );
        }

        instructionRequestV3.setSuccess(successRegistrationV3s);
        instructionRequestV3.setFailure(failureRegistrationV3);
        return instructionRequestV3;
    }

    private List<EnsuredUser> ensureUsers(DbaasAdapter adapter, AdditionalRoles additionalRole, List<String> missedRoles) {
        List<EnsuredUser> ensuredUsers = new ArrayList<>();

        for (String missedRole : missedRoles) {
            // TODO during opensearch logical database migration `additionalRole.getDbName()` is null
            EnsuredUser ensuredUser = Failsafe.with(buildRetryPolicy(RETRY_ATTEMPT, RETRY_DELAY)).get(() ->
                    adapter.ensureUser(null,
                            null,
                            additionalRole.getDbName(),
                            missedRole)
            );
            ensuredUsers.add(ensuredUser);
        }
        return ensuredUsers;
    }

    private List<String> getMissedRoles(List<String> requiredRoles, List<String> existRoles) {
        return requiredRoles.stream().filter(role -> !existRoles.contains(role)).toList();
    }

    @Transactional
    public void updateConnectionAndInstruction(InstructionRequestV3 instructionRequestV3, String physicalDatabaseId) {
        PhysicalDatabaseInstruction instruction = physicalDatabaseInstructionRepository.findAndLockInstruction(physicalDatabaseId);

        try {
            List<SuccessRegistrationV3> successRegistrationV3s = instructionRequestV3.getSuccess();
            List<AdditionalRoles> additionalRoles = convertStringToList(instruction.getContext());

            instructionService.saveConnectionPropertiesAfterRolesRegistration(successRegistrationV3s);
            instructionService.updateInstructionWithContext(instruction, instructionService.getFinalInstructionToSet(successRegistrationV3s, additionalRoles));

            if (instructionRequestV3.getFailure() != null) {
                instruction.setFailure(instructionRequestV3.getFailure());
                physicalDatabaseInstructionRepository.save(instruction);
                return;
            }

            if (getNextPortion(instruction.getId().toString(), instruction.getContext()) == null) {
                instructionService.completeMigrationProcedure(physicalDatabaseId, instruction.getId().toString());
            }
        } catch (JsonProcessingException ex) {
            log.warn("Exception occurred during updating connections and instruction", ex);
            instruction.setFailure(new FailureRegistrationV3("", ex.getMessage()));
            physicalDatabaseInstructionRepository.save(instruction);
        }
    }

    private Map<String, Object> createInstruction(
            PhysicalDatabase physicalDatabase,
            List<Database> logicalDatabases,
            PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase
    ) throws JsonProcessingException {
        log.info("Database with set instruction does not exist, new one should be created");

        Instruction instruction = instructionService.buildInstructionForAdditionalRoles(logicalDatabases);
        return instructionService.saveInstruction(
                physicalDatabase,
                instruction,
                requestedPhysicalDatabase
        );
    }

    private void validateRequest(PhysicalDatabaseRegistryRequestV3 parameters) {
        URI uri = URI.create(parameters.getAdapterAddress());
        String host = uri.getHost();
        String protocol = uri.getScheme();

        if (host == null || protocol == null) {
            throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4045,
                    ErrorCodes.CORE_DBAAS_4045.getDetail(parameters.getAdapterAddress()), Source.builder().pointer("/adapterAddress").build());
        }
    }

    //TODO move to appropriate place
    private Instruction recreateInstruction(PhysicalDatabaseInstruction existingInstruction, List<Database> logicalDatabases) {
        instructionService.deleteInstruction(String.valueOf(existingInstruction.getId()));
        Instruction instructionCreated = instructionService.buildInstructionForAdditionalRoles(logicalDatabases);
        return instructionCreated;
    }

    private SimpleLock lockPhysicalDatabaseRegistration(String physicalDatabaseId) {
        Optional<SimpleLock> optLock = lockService.getKeepAliveLock(getLockConfiguration(physicalDatabaseId));

        if (optLock.isEmpty()) {
            log.debug("PhysicalDatabase `{}` was already locked for internal migration", physicalDatabaseId);
            return null;
        }

        log.debug("PhysicalDatabase `{}` was locked for internal migration successfully", physicalDatabaseId);
        return optLock.get();
    }

    private boolean rolesDifferentAndMultiUsersEnabled(PhysicalDatabase physicalDatabase, PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        Boolean rolesDifferent = instructionService.isRolesDifferent(requestedPhysicalDatabase.getMetadata().getSupportedRoles(), physicalDatabase.getRoles());
        boolean multiUsersEnabled = requestedPhysicalDatabase.getMetadata().getFeatures().get("multiusers"); //TODO throw NPE if metadata = null or 'mutiusers' doesn't exist

        return rolesDifferent && multiUsersEnabled;
    }

    private boolean statusRunningAndApiVersionV2(PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        boolean isStatusRunning = requestedPhysicalDatabase.getStatus().equals("running");
        boolean isApiVersionV2 = requestedPhysicalDatabase.getMetadata().getApiVersion().equals("v2");
        return isStatusRunning && isApiVersionV2;
    }

    public List<Database> getLogicalDatabasesForMigration(String phydbid, String type, List<String> roles) {
        List<Database> databases = getDatabasesByPhysDbAndType(phydbid, type);
        List<Database> databasesForMigration = new ArrayList<>();
        for (Database database : databases) {
            if (database.isMarkedForDrop()) continue;
            Set<String> supportedRoles = database.getConnectionProperties()
                    .stream()
                    .map(stringObjectMap -> (String) stringObjectMap.get("role"))
                    .collect(Collectors.toSet());

            for (String role : roles) {
                if (!supportedRoles.contains(role)) {
                    databasesForMigration.add(database);
                    break;
                }
            }
        }
        return databasesForMigration;
    }
}
