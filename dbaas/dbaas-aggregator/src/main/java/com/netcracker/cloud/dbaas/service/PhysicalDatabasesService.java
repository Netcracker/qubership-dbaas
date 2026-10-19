package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.netcracker.cloud.dbaas.dto.*;
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
import static com.netcracker.cloud.dbaas.service.DBaaService.getDatabaseName;
import static com.netcracker.cloud.dbaas.service.LockService.getLockConfiguration;
import static com.netcracker.cloud.dbaas.utils.RetryPolicyUtil.buildAdapterRetryPolicy;
import static jakarta.ws.rs.core.Response.Status.*;
import static java.util.Collections.emptyList;

@ApplicationScoped
@Slf4j
public class PhysicalDatabasesService {
    private final static String MESSAGE_UPDATING_EXISTING_DATABASE = "Updating existing database with phydbid = {}, adapterAddress = {}";
    private final static String MESSAGE_DATABASE_UPDATED = "Database updated";

    private static final int PORTION_SIZE = 50;
    private static final ObjectMapper objectMapper = new ObjectMapper();

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
    LockService lockService;
    @Inject
    AsyncOperations asyncOperations;
    @Inject
    PhysicalDatabaseInstructionRepository physicalDatabaseInstructionRepository;
    @Inject
    DBaaService dBaaService;

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

    @Deprecated(forRemoval = true)
    public List<Database> getDatabasesByPhysDbAndType(String phydbid, String type) {
        String adapterId = physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(phydbid).getAdapter().getAdapterId();
        return logicalDbDbaasRepository.getDatabaseDbaasRepository().findDatabasesByAdapterIdAndType(adapterId, type, false);
    }

    public List<Database> getDatabasesForMigration(String adapterId, String type, List<String> requiredRoles) {
        return logicalDbDbaasRepository.getDatabaseDbaasRepository().findDatabasesForMigration(adapterId, type, requiredRoles, PORTION_SIZE);
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
    ) {
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

        if (isPhysicalDatabaseRegistrationLocked(physicalDatabaseId, type)) {
            return Response.accepted().build();
        }

        if (isMigrationRequired(physicalDatabase, requestedPhysicalDatabase)) {
            List<Database> logicalDatabases = getDatabasesForMigration(physicalDatabase.getAdapter().getAdapterId(),
                    type,
                    requestedPhysicalDatabase.getMetadata().getSupportedRoles());

            if (!logicalDatabases.isEmpty()) {
                return startMigration(
                        physicalDatabaseId,
                        requestedPhysicalDatabase,
                        logicalDatabases,
                        internalMigration
                );
            }
        }

        if (isDbActual(requestedPhysicalDatabase, physicalDatabase)) {
            log.info("Adapter '{}' update is not required", physicalDatabase.getPhysicalDatabaseIdentifier());
        } else {
            writeChanges(physicalDatabaseId, requestedPhysicalDatabase, physicalDatabase);
        }

        return Response.ok().build();
    }

    @Transactional
    public Response handleInstruction(UUID instructionId, InstructionRequestV3 parameters) {
        Optional<PhysicalDatabaseInstruction> optInstruction =
                physicalDatabaseInstructionRepository.findByIdOptional(instructionId);

        log.info("current instruction = {}", optInstruction);
        if (optInstruction.isEmpty()) {
            log.error("Instruction with Id = {} not found", instructionId);
            return Response.status(NOT_FOUND)
                    .entity(String.format("Instruction with Id = %s not found", instructionId))
                    .build();
        }

        if (parameters.getSuccess() != null) {
            saveConnectionPropertiesAfterRolesRegistration(parameters.getSuccess());
        }
        if (parameters.getFailure() != null) {
            deleteInstruction(instructionId);
            return Response.status(INTERNAL_SERVER_ERROR)
                    .entity("An error has occurred:" + parameters.getFailure().getMessage())
                    .build();
        }

        PhysicalDatabaseInstruction instruction = optInstruction.get();
        PhysicalDatabase physicalDatabase = getByPhysicalDatabaseIdentifier(instruction.getPhysicalDatabaseId());
        List<Database> databasesForMigration = getDatabasesForMigration(
                physicalDatabase.getAdapter().getAdapterId(),
                physicalDatabase.getType(),
                instruction.getPhysicalDbRegRequest().getMetadata().getSupportedRoles()
        );

        if (databasesForMigration.isEmpty()) {
            log.info("All logical databases processed in instruction with id = {}", instructionId);
            completeMigrationProcedure(instruction.getPhysicalDatabaseId(), instructionId, instruction.getPhysicalDbRegRequest());
            return Response.ok().build();
        }

        List<AdditionalRoles> additionalRoles =
                buildInstructionForAdditionalRoles(instructionId, databasesForMigration)
                        .getAdditionalRoles();
        return Response.accepted().entity(additionalRoles).build();
    }


    private Response startMigration(
            String physicalDatabaseId,
            PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase,
            List<Database> logicalDatabases,
            boolean internalMigration
    ) {
        if (!isStatusRunningAndApiVersionV2(requestedPhysicalDatabase)) {
            log.warn("Adapter status run or adapter version is not equals v2");
            return Response.status(BAD_REQUEST)
                    .entity("Adapter status run or adapter version is not equals v2")
                    .build();
        }

        PhysicalDatabaseInstruction physicalDatabaseInstruction = findInstructionByPhyDbId(physicalDatabaseId);

        if (physicalDatabaseInstruction == null) {
            Instruction instruction = buildInstructionForAdditionalRoles(logicalDatabases);
            physicalDatabaseInstruction = saveInstructionIntoDB(instruction.getId(), physicalDatabaseId, requestedPhysicalDatabase);
        }

        if (internalMigration) {
            return startInternalMigration(logicalDatabases, physicalDatabaseInstruction);
        }

        Instruction instruction = buildInstructionForAdditionalRoles(physicalDatabaseInstruction.getId(), logicalDatabases);
        log.info("Got instruction with portion = {}", instruction);
        return Response.accepted(Map.of("instruction", instruction)).build();
    }

    private Response startInternalMigration(
            List<Database> logicalDatabases,
            PhysicalDatabaseInstruction instruction
    ) {
        String physicalDatabaseId = instruction.getPhysicalDatabaseId();
        String type = logicalDatabases.getFirst().getDatabaseRegistry().getFirst().getType();
        SimpleLock lock = lockPhysicalDatabaseRegistration(physicalDatabaseId, type);
        Response response = Response.accepted().build();

        if (lock != null) {
            if (!instruction.getFailure().isEmpty()) {
                log.warn("Previous migration failed, instruction will be deleted. Failures {}", instruction.getFailure());
                response = Response.serverError().entity(instruction.getFailure()).build();
            }

            startAsyncInternalMigration(instruction, logicalDatabases, lock);
        }
        return response;
    }

    public void startAsyncInternalMigration(
            PhysicalDatabaseInstruction instruction,
            List<Database> logicalDatabases,
            SimpleLock simpleLock
    ) {
        CompletableFuture.runAsync(asyncOperations.wrapWithContext(() -> {
                            processInternalMigration(instruction, logicalDatabases);
                        }),
                        asyncOperations.getMigrationExecutor())
                .whenComplete((_, t) -> {
                            try {
                                if (t != null) {
                                    log.warn("Exception thrown while internal migration process", t);
                                    instruction.getFailure().add(new FailureRegistrationV3("", t.toString()));
                                    physicalDatabaseInstructionRepository.save(instruction);
                                }
                            } finally {
                                lockService.unlock(simpleLock);
                            }
                        }
                );
    }

    private void processInternalMigration(
            PhysicalDatabaseInstruction instruction,
            List<Database> logicalDatabases
    ) {
        List<String> requiredRoles = instruction.getPhysicalDbRegRequest().getMetadata().getSupportedRoles();

        DbaasAdapter adapter = Failsafe.with(buildAdapterRetryPolicy(RETRY_ATTEMPT, RETRY_DELAY))
                .get(() -> getAdapterByPhysDbId(instruction.getPhysicalDatabaseId()));

        do {
            InstructionRequestV3 instructionRequest = runLogicalDatabaseMigration(adapter, logicalDatabases, requiredRoles);
            updateConnectionAndInstruction(instruction, instructionRequest);
            logicalDatabases = getDatabasesForMigration(adapter.identifier(), adapter.type(), requiredRoles, instructionRequest.getFailure());
        } while (!logicalDatabases.isEmpty());
    }

    private List<Database> getDatabasesForMigration(String adapterId, String type, List<String> requiredRoles, FailureRegistrationV3 failure) {
        return failure != null ? emptyList() : getDatabasesForMigration(adapterId, type, requiredRoles);
    }

    private InstructionRequestV3 runLogicalDatabaseMigration(
            DbaasAdapter adapter,
            List<Database> databasesForMigration,
            List<String> requiredRoles
    ) {
        InstructionRequestV3 instructionRequestV3 = new InstructionRequestV3();
        List<SuccessRegistrationV3> successRegistrationV3s = new ArrayList<>();
        FailureRegistrationV3 failureRegistrationV3 = null;

        for (Database database : databasesForMigration) {
            List<Map<String, Object>> connectionProperties = new ArrayList<>();
            List<DbResource> resources = new ArrayList<>();
            List<String> existRoles = database.getConnectionProperties().stream().map(cp -> (String) cp.get("role")).toList();
            List<String> missedRoles = getMissedRoles(requiredRoles, existRoles);
            List<EnsuredUser> ensuredUsers;

            try {
                ensuredUsers = ensureUsers(adapter, getDatabaseName(database), missedRoles);
            } catch (Exception e) {
                log.warn("Got exception during ensure user, migration will be stopped.", e);
                failureRegistrationV3 = new FailureRegistrationV3();
                failureRegistrationV3.setId(database.getId().toString());
                failureRegistrationV3.setMessage(e.getMessage());
                break;
            }

            for (EnsuredUser ensuredUser : ensuredUsers) {
                connectionProperties.add(ensuredUser.getConnectionProperties());
                resources.addAll(ensuredUser.getResources());
            }

            successRegistrationV3s.add(new SuccessRegistrationV3(
                            database.getId(),
                            connectionProperties,
                            resources
                    )
            );
        }

        instructionRequestV3.setSuccess(successRegistrationV3s);
        instructionRequestV3.setFailure(failureRegistrationV3);
        return instructionRequestV3;
    }

    private List<EnsuredUser> ensureUsers(DbaasAdapter adapter, String dbName, List<String> missedRoles) {
        List<EnsuredUser> ensuredUsers = new ArrayList<>();

        for (String missedRole : missedRoles) {
            EnsuredUser ensuredUser = Failsafe.with(buildAdapterRetryPolicy(RETRY_ATTEMPT, RETRY_DELAY)).get(() ->
                    adapter.ensureUser(null,
                            null,
                            dbName,
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
    public void updateConnectionAndInstruction(PhysicalDatabaseInstruction instruction, InstructionRequestV3 instructionRequestV3) {
        List<SuccessRegistrationV3> successRegistrationV3s = instructionRequestV3.getSuccess();
        saveConnectionPropertiesAfterRolesRegistration(successRegistrationV3s);

        if (!instruction.getFailure().isEmpty()) {
            List<FailureRegistrationV3> failedEnsureUsers = successRegistrationV3s.stream()
                    .flatMap(s -> instruction.getFailure().stream()
                            .filter(f -> !f.getId().isBlank() && s.getId().toString().equals(f.getId())))
                    .toList();
            instruction.setFailure(failedEnsureUsers);
        }

        if (instructionRequestV3.getFailure() != null && !instruction.getFailure().contains(instructionRequestV3.getFailure())) {
            instruction.getFailure().add(instructionRequestV3.getFailure());
            physicalDatabaseInstructionRepository.save(instruction);
            return;
        }

        completeMigrationProcedure(instruction.getPhysicalDatabaseId(), instruction.getId(), instruction.getPhysicalDbRegRequest());
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

    private SimpleLock lockPhysicalDatabaseRegistration(String physicalDatabaseId, String type) {
        String lockName = String.format("roles-migration-%s-%s", type, physicalDatabaseId);
        Optional<SimpleLock> optLock = lockService.getKeepAliveLock(getLockConfiguration(lockName));

        if (optLock.isEmpty()) {
            log.debug("PhysicalDatabase `{}` was already locked for internal migration", physicalDatabaseId);
            return null;
        }

        log.debug("PhysicalDatabase `{}` was locked for internal migration successfully", physicalDatabaseId);
        return optLock.get();
    }

    private boolean isMigrationRequired(PhysicalDatabase physicalDatabase, PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        Boolean rolesDifferent = isRolesDifferent(requestedPhysicalDatabase.getMetadata().getSupportedRoles(), physicalDatabase.getRoles());
        boolean multiUsersEnabled = requestedPhysicalDatabase.getMetadata().getFeatures().get("multiusers"); //TODO throw NPE if metadata = null or 'mutiusers' doesn't exist

        return rolesDifferent && multiUsersEnabled;
    }

    private boolean isStatusRunningAndApiVersionV2(PhysicalDatabaseRegistryRequestV3 requestedPhysicalDatabase) {
        boolean isStatusRunning = requestedPhysicalDatabase.getStatus().equals("running");
        boolean isApiVersionV2 = requestedPhysicalDatabase.getMetadata().getApiVersion().equals("v2");
        return isStatusRunning && isApiVersionV2;
    }

    private boolean isPhysicalDatabaseRegistrationLocked(String physicalDatabaseId, String type) {
        return lockService.isLocked(String.format("roles-migration-%s-%s", type, physicalDatabaseId));
    }

    public Boolean isRolesDifferent(List<String> recievedRoles, List<String> currentSupportedRoles) {
        recievedRoles.replaceAll(String::toLowerCase);
        //check that additional roles exist
        Collections.sort(recievedRoles);
        Collections.sort(currentSupportedRoles);
        return !currentSupportedRoles.equals(recievedRoles);
    }

    public Instruction buildInstructionForAdditionalRoles(List<Database> logicalDatabases) {
        return buildInstructionForAdditionalRoles(UUID.randomUUID(), logicalDatabases);
    }

    public Instruction buildInstructionForAdditionalRoles(UUID instructionId, List<Database> logicalDatabases) {
        Instruction instruction = new Instruction();
        instruction.setId(instructionId);
        List<AdditionalRoles> additionalRolesList = new ArrayList<>();
        for (Database database : logicalDatabases) {
            AdditionalRoles additionalRoles = new AdditionalRoles();
            additionalRoles.setId(database.getId());
            additionalRoles.setConnectionProperties(database.getConnectionProperties());
            additionalRoles.setResources(database.getResources());
            additionalRoles.setDbName(database.getName());
            additionalRolesList.add(additionalRoles);
        }
        instruction.setAdditionalRoles(additionalRolesList);
        return instruction;
    }

    public void completeMigrationProcedure(
            String physicalDatabaseId,
            UUID instructionId,
            PhysicalDatabaseRegistryRequestV3 requestV3
    ) {
        log.info("Saving supported roles in physical database with id = {}", physicalDatabaseId);
        savePhysicalDatabaseWithRoles(physicalDatabaseId, requestV3);
        log.info("Removing instruction after saving roles by id = {}", instructionId);
        deleteInstruction(instructionId);
    }

    protected PhysicalDatabaseInstruction saveInstructionIntoDB(UUID instructionId, String physicalDatabaseId, PhysicalDatabaseRegistryRequestV3 parameters) {
        PhysicalDatabaseInstruction physicalDatabaseInstruction = new PhysicalDatabaseInstruction();
        physicalDatabaseInstruction.setId(instructionId);
        physicalDatabaseInstruction.setPhysicalDatabaseId(physicalDatabaseId);
        physicalDatabaseInstruction.setContext("[]"); // resolves as blank list
        physicalDatabaseInstruction.setPhysicalDbRegRequest(parameters);
        physicalDatabaseInstruction.setInstructionType(InstructionType.MULTIUSERS_MIGRATION);
        Date timeDbInstructionCreation = new Date();
        physicalDatabaseInstruction.setTimeCreation(timeDbInstructionCreation);

        log.info("Saving instruction");
        physicalDatabaseInstructionRepository.persist(physicalDatabaseInstruction);
        log.info("PhysicalDatabaseInstruction saved with instruction");
        return physicalDatabaseInstruction;
    }

    public void deleteInstruction(UUID instructionId) {
        Optional<PhysicalDatabaseInstruction> physicalDatabaseInstruction = physicalDatabaseInstructionRepository.findByIdOptional(instructionId);
        if (physicalDatabaseInstruction.isPresent()) {
            log.info("Deleting instruction data from physicalDatabaseInstruction = {}", instructionId);
            physicalDatabaseInstructionRepository.deleteById(physicalDatabaseInstruction.get().getId());
        } else {
            log.info("no instruction found in physicalDatabaseInstruction with id = {}", instructionId);
        }
    }

    public PhysicalDatabaseInstruction findInstructionByPhyDbId(String physicalDatabaseIdentifier) {
        return physicalDatabaseInstructionRepository.findByPhysicalDatabaseId(physicalDatabaseIdentifier);
    }

    public void saveConnectionPropertiesAfterRolesRegistration(List<SuccessRegistrationV3> successRegistrationList) {
        for (SuccessRegistrationV3 successRegistration : successRegistrationList) {
            dBaaService.updateDatabaseConnectionPropertiesAndResourcesById(successRegistration.getId(),
                    successRegistration.getConnectionProperties(), successRegistration.getResources());
        }
        log.info("All processed logical databases successfully saved");
    }
}
