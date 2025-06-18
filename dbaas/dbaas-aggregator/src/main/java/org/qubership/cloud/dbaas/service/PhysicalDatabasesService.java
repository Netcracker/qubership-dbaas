package org.qubership.cloud.dbaas.service;

import com.google.common.collect.Maps;
import org.qubership.cloud.dbaas.dto.PhysicalDatabaseRegistrationBuilder;
import org.qubership.cloud.dbaas.dto.RegisteredPhysicalDatabasesDTO;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import org.qubership.cloud.dbaas.dto.v3.Metadata;
import org.qubership.cloud.dbaas.dto.v3.PhysicalDatabaseRegistrationResponseDTOV3;
import org.qubership.cloud.dbaas.dto.v3.PhysicalDatabaseRegistryRequestV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.exceptions.AdapterUnavailableException;
import org.qubership.cloud.dbaas.exceptions.PhysicalDatabaseRegistrationConflictException;
import org.qubership.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.cloud.dbaas.DbaasApiPath.VERSION_2;

@ApplicationScoped
@Slf4j
public class PhysicalDatabasesService {

    private final static String MESSAGE_UPDATING_EXISTING_DATABASE = "Updating existing database with phydbid = {}, adapterAddress = {}";
    private final static String MESSAGE_DATABASE_UPDATED = "Database updated";

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
        return physicalDatabaseCache.computeIfAbsent(phyDbId, id -> getByPhysicalDatabaseIdentifier(phyDbId));
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
}
