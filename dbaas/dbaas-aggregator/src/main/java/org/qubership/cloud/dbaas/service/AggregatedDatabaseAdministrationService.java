package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.AbstractDatabaseCreateRequest;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseCreateRequestV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3SingleCP;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.service.dbsettings.LogicalDbSettingsService;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.beanutils.PropertyUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.service.AbstractDbaasAdapterRESTClient.MICROSERVICE_NAME;
import static org.qubership.cloud.dbaas.service.PasswordEncryption.PASSWORD_FIELD;
import static org.postgresql.util.PSQLState.UNIQUE_VIOLATION;

@Slf4j
@ApplicationScoped
public class AggregatedDatabaseAdministrationService {

    private static final String MESSAGE_ERROR_DURING_UPDATE_DATABASE = "Error during update database with classifier {} and type {}";

    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @Inject
    DBaaService dBaaService;
    @Inject
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @Inject
    PhysicalDatabasesService physicalDatabasesService;
    @Inject
    BgNamespaceRepository bgNamespaceRepository;
    @ConfigProperty(name = "dbaas.paas.pod-name")
    String podName;
    @Inject
    LogicalDbSettingsService logicalDbSettingsService;

    ExecutorService executorService;

    @PostConstruct
    void init() {
        executorService = Executors.newFixedThreadPool(10);
    }

    public Response createDatabaseFromRequest(DatabaseCreateRequestV3 createRequest, String namespace,
                                              FunctionProvidePassword<Database, String> password,
                                              String serviceRole, String version) {
        return createDatabaseFromRequest(createRequest, namespace, password, serviceRole, version, false);
    }

    public Response createDatabaseFromRequest(DatabaseCreateRequestV3 createRequest, String namespace,
                                              FunctionProvidePassword<Database, String> password,
                                              String serviceRole, String version, Boolean async) {
        log.info("Request to get or create {} database in {} with classifier {}", createRequest.getType(), namespace, createRequest.getClassifier());
        if (createRequest.getType() == null || createRequest.getType().isEmpty()) { // regardless @Notnull it can be Null
            throw new DBCreateValidationException(Source.builder().pointer("/databases").build(), "request parameter 'type' can't be null or empty");
        }
        TreeMap<String, Object> classifier = classifierFromCreateRequest(createRequest, namespace);

        Optional<BgDomain> bgDomain = getBgDomain(namespace);
        if (bgDomain.isPresent()) {
            saveExtraClassifierForDatabaseBaseOnBgDomain(bgDomain.get(), createRequest.getType(), namespace, classifier);
        }

        createRequest.setBackupDisabled(createRequest.getBackupDisabled() != null ?
                createRequest.getBackupDisabled() : false); // Install default value

        SortedMap<String, Object> declarativeClassifier = new TreeMap<>(classifier);
        if (SCOPE_VALUE_TENANT.equals(declarativeClassifier.get(SCOPE))) {
            declarativeClassifier.remove(TENANT_ID);
        }
        log.debug("version in request= {}", version);
        log.debug("try to find declaration with classifier = {} and type = {}", declarativeClassifier, createRequest.getType());
        Optional<DatabaseDeclarativeConfig> lastDeclarativeConfig = declarativeConfigRepository.
                findFirstByClassifierAndType(declarativeClassifier, createRequest.getType());
        if (lastDeclarativeConfig.isPresent()) {
            log.debug("found declaration = {}", lastDeclarativeConfig);
            createRequest = new DatabaseCreateRequestV3(lastDeclarativeConfig.get(), createRequest.getOriginService(),
                    createRequest.getUserRole());
            version = evaluateVersion(version, lastDeclarativeConfig.get().getVersioningType(), namespace);
        }
        log.debug("version after apply declarative config= {}", version);

        final DatabaseRegistry databaseRegistry = createDatabaseRegistryEntity(createRequest, namespace, classifier);
        Database database = createDatabaseEntity(createRequest, databaseRegistry, version);
        log.info("Request to get or create logical database {} in physical database {}"
                , database
                , createRequest.getPhysicalDatabaseId());
        try {
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);
        } catch (ConstraintViolationException ex) {
            log.debug("try to get database={}", databaseRegistry);
            if (AggregatedDatabaseAdministrationUtils.isUniqueViolation(ex)) {
                //In blue-green we can update only versioned db
                return updateExistingDatabase(createRequest, password, serviceRole, classifier, bgDomain, version, lastDeclarativeConfig);
            } else {
                throw new UnknownErrorCodeException(ex);
            }
        } catch (QuarkusTransactionException ex) {
            log.debug("Transaction Exception has occurred", ex);
            return getResponseEntity(createRequest, password, classifier, ex, false);
        }
        boolean isOwner = Objects.equals(createRequest.getOriginService(), createRequest.getClassifier().get(MICROSERVICE_NAME));
        if (!isOwner && !serviceRole.equals(Role.ADMIN.toString())) {
            databaseRegistryDbaasRepository.delete(databaseRegistry);
            throw new NotSupportedServiceRoleException();
        }
        return createNewDatabase(createRequest, namespace, password, serviceRole,
                // current databaseRegistry entity belongs to other transaction - we need to get new entity from repository
                databaseRegistryDbaasRepository.findDatabaseRegistryById(databaseRegistry.getId()).get(), async);
    }

    private String evaluateVersion(String version, String versioningType, String namespace) {
        if (STATIC_STATE.equals(versioningType)) {
            return null;
        } else if (version == null) {
            Optional<BgNamespace> optionalBgNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(namespace);
            if (optionalBgNamespace.isPresent()) {
                return optionalBgNamespace.get().getVersion();
            }
        }

        return version;
    }

    @NotNull
    public Optional<BgDomain> getBgDomain(String namespace) {
        Optional<BgNamespace> bgNamespaceByNamespace = bgNamespaceRepository.findBgNamespaceByNamespace(namespace);
        log.debug("founded bgNamespace = {}", bgNamespaceByNamespace);
        return bgNamespaceByNamespace.map(BgNamespace::getBgDomain);
    }

    @Transactional
    public void saveExtraClassifierForDatabaseBaseOnBgDomain(BgDomain bgDomain, String type, String namespace, SortedMap<String, Object> classifier) {
        Optional<DatabaseRegistry> targetDatabaseRegistry = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, type);
        if (targetDatabaseRegistry.isPresent()) {
            log.debug("target database registry already exists");
            return;
        }
        Optional<BgNamespace> anotherBgNamespace = bgDomain.getNamespaces().stream().
                filter(ns -> !ns.getNamespace().equals(namespace)
                        && (ACTIVE_STATE.equals(ns.getState()) || CANDIDATE_STATE.equals(ns.getState()))).findFirst();
        if (anotherBgNamespace.isEmpty()) {
            log.debug("suitable for db sharing namespace in bgDomain = {} is not present ", bgDomain.getNamespaces());
            return;
        }
        String anotherNamespace = anotherBgNamespace.get().getNamespace();
        SortedMap<String, Object> sourceClassifier = new TreeMap<>(classifier);
        sourceClassifier.put(NAMESPACE, anotherNamespace);

        Optional<DatabaseRegistry> sourceDatabaseRegistry = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(sourceClassifier, type);
        if (sourceDatabaseRegistry.isEmpty()) {
            log.debug("symmetric database classifier is not present");
            return;
        }
        Database sourceDatabase = sourceDatabaseRegistry.get().getDatabase();
        if (sourceDatabase.getBgVersion() == null) {
            DatabaseRegistry databaseRegistry = new DatabaseRegistry();
            databaseRegistry.setClassifier(classifier);
            databaseRegistry.setNamespace(namespace);
            databaseRegistry.setTimeDbCreation(new Date());
            databaseRegistry.setType(type);
            databaseRegistry.setDatabase(sourceDatabase);
            sourceDatabase.getDatabaseRegistry().add(databaseRegistry);

            log.info("save new classifier = {}", databaseRegistry);
            log.debug("update database = {}", sourceDatabase);
            databaseRegistryDbaasRepository.saveAnyTypeLogDb(databaseRegistry);
        }
    }

    @NotNull
    private Response updateExistingDatabase(AbstractDatabaseCreateRequest createRequest,
                                            FunctionProvidePassword<Database, String> password,
                                            String serviceRole,
                                            TreeMap<String, Object> classifier, Optional<BgDomain> allBgState,
                                            String version, Optional<DatabaseDeclarativeConfig> declarativeConfig) {
        log.debug("is unique violation exception");
        DatabaseRegistry databaseRegistry = dBaaService.findDatabaseByClassifierAndType(classifier, createRequest.getType(), true);
        if (databaseRegistry != null) {
            if (Boolean.TRUE.equals(dBaaService.isModifiedFields(createRequest, databaseRegistry.getDatabase()))) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Database with such classifier already exists, but unmodified fields (backupDisabled) can not be modified").build();
            }
            try {
                if (version != null && allBgState.isPresent() && declarativeConfig.isPresent()
                        && VERSION_STATE.equals(declarativeConfig.get().getVersioningType())) {
                    if (!version.equals(databaseRegistry.getDatabase().getBgVersion())) {
                        databaseRegistry.getDatabase().setBgVersion(version);
                        databaseRegistryDbaasRepository.saveInternalDatabase(databaseRegistry);
                    }
                } else {
                    if (databaseRegistry.getDatabase().getBgVersion() != null) {
                        databaseRegistry.getDatabase().setBgVersion(null);
                        databaseRegistryDbaasRepository.saveInternalDatabase(databaseRegistry);
                    }
                }
                updateDatabase(databaseRegistry, createRequest);
            } catch (WebApplicationException e) {
                log.error(MESSAGE_ERROR_DURING_UPDATE_DATABASE, classifier, createRequest.getType());
                return Response.status(e.getResponse().getStatusInfo()).entity(String.format("Updating database failed with error: %s %s, message: %s", e.getResponse().getStatus(), e.getResponse().getStatusInfo().getReasonPhrase(), e.getResponse().getEntity())).build();
            } catch (Exception e) {
                log.error(MESSAGE_ERROR_DURING_UPDATE_DATABASE, classifier, createRequest.getType());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Updating database failed with error: " + e.getMessage()).build();
            }
            log.info("Skip database creation. Requested to create already registered {} database: {}", createRequest.getType(), classifier);
            databaseRegistry = dBaaService.detach(databaseRegistry);
            preResponseProcessing(databaseRegistry, password);
            log.info("end of preResponseProcessing");

            // database was requested but not created yet
            if (DbState.DatabaseStateStatus.PROCESSING.equals(databaseRegistry.getDatabase().getDbState().getDatabaseState())) {
                log.debug("Database {} is not created yet", classifier);
                return Response.accepted(dBaaService.processConnectionPropertiesV3(databaseRegistry, serviceRole)).build();
            }
            return Response.ok(dBaaService.processConnectionPropertiesV3(databaseRegistry, serviceRole)).build();
        } else {
            throw new DBCreationConflictException(String.format("Duplicate database of type '%s' was found, classifier: %s.", createRequest.getType(), classifier));
        }
    }

    @NotNull
    private Response createNewDatabase(AbstractDatabaseCreateRequest createRequest,
                                       String namespace,
                                       FunctionProvidePassword<Database, String> password,
                                       String serviceRole,
                                       DatabaseRegistry databaseRegistry,
                                       Boolean async) {
        String microserviceName = (String) databaseRegistry.getClassifier().get(MICROSERVICE_NAME);
        if (!dBaaService.isAdapterExists(createRequest, namespace, microserviceName)) {
            final UnregisteredPhysicalDatabaseException exception = new UnregisteredPhysicalDatabaseException(AggregatedDatabaseAdministrationServiceConst.NO_ADAPTER_MSG);
            exception.setStatus(Response.Status.NOT_FOUND.getStatusCode());
            deleteDatabase(databaseRegistry, null);
            throw exception;
        }

        if (Boolean.TRUE.equals(async)) {
            executorService.submit(() -> createNewDatabase(createRequest, namespace, password, serviceRole, databaseRegistry));

            DatabaseRegistry responseDatabaseRegistry = createCopyForResponse(databaseRegistry);
            preResponseProcessing(responseDatabaseRegistry, password);
            return Response.accepted(dBaaService.processConnectionProperties(responseDatabaseRegistry)).build();
        } else {
            return createNewDatabase(createRequest, namespace, password, serviceRole, databaseRegistry);
        }
    }

    @NotNull
    private Response createNewDatabase(AbstractDatabaseCreateRequest createRequest,
                                       String namespace,
                                       FunctionProvidePassword<Database, String> password,
                                       String serviceRole,
                                       DatabaseRegistry databaseRegistry) {
        CreatedDatabaseV3 createdDatabase = null;
        try {
            log.debug("try to create database");
            String microserviceName = (String) databaseRegistry.getClassifier().get(MICROSERVICE_NAME);
            Optional<CreatedDatabaseV3> optCreatedDatabase = dBaaService.createDatabase(createRequest, namespace, microserviceName);
            createdDatabase = optCreatedDatabase.get();

            enrichDatabaseEntity(databaseRegistry, createdDatabase, password, Role.ADMIN.toString());

            DatabaseRegistry responseDatabaseRegistry = createCopyForResponse(databaseRegistry);
            dBaaService.encryptAndSaveDatabaseEntity(databaseRegistry);
            dBaaService.getConnectionPropertiesService().addAdditionalPropToCP(responseDatabaseRegistry);
            log.info("New database was created {}", responseDatabaseRegistry.getDatabase());
            return AggregatedDatabaseAdministrationUtils.responseDatabaseCreated(responseDatabaseRegistry,
                    physicalDatabasesService.getByAdapterId(responseDatabaseRegistry.getDatabase().getAdapterId()).getPhysicalDatabaseIdentifier(), serviceRole);
        } catch (Exception e) {
            log.error("Exception during database creation with classifier = {}", databaseRegistry.getClassifier(), e);
            deleteDatabase(databaseRegistry, createdDatabase);
            throw e;
        }
    }

    private DatabaseRegistry createCopyForResponse(DatabaseRegistry databaseRegistry) {
        DatabaseRegistry responseDatabaseRegistry = new DatabaseRegistry();
        Database responseDatabase = new Database();
        responseDatabaseRegistry.setDatabase(responseDatabase);
        try {
            PropertyUtils.copyProperties(responseDatabaseRegistry, databaseRegistry);
            PropertyUtils.copyProperties(responseDatabase, databaseRegistry.getDatabase());
            responseDatabaseRegistry.setDatabase(responseDatabase);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        return responseDatabaseRegistry;
    }

    private DatabaseRegistry createDatabaseRegistryEntity(AbstractDatabaseCreateRequest createRequest, String namespace, TreeMap<String, Object> classifier) {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setNamespace(namespace);
        databaseRegistry.setType(createRequest.getType());
        return databaseRegistry;
    }

    private Database createDatabaseEntity(AbstractDatabaseCreateRequest createRequest,
                                          DatabaseRegistry databaseRegistry,
                                          @Nullable String bgVersion) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(databaseRegistry.getClassifier());
        database.setNamespace(databaseRegistry.getNamespace());
        database.setType(createRequest.getType());
        database.setBackupDisabled(createRequest.getBackupDisabled());
        database.setSettings(createRequest.getSettings());
        database.setConnectionProperties(Collections.emptyList());
        database.setDbState(new DbState(DbState.DatabaseStateStatus.PROCESSING, podName));
        database.setBgVersion(bgVersion);

        Optional<DatabaseRegistry> databaseRegistryByClassifierAndType = databaseRegistryRepository.
                findDatabaseRegistryByClassifierAndType(databaseRegistry.getClassifier(), createRequest.getType());
        log.debug("founded database registry = {} by while creating Database entity", databaseRegistryByClassifierAndType);
        databaseRegistry.setDatabase(database);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        log.debug("created Database entity= {}", database);
        log.debug("created Database classifier entity= {}", database.getDatabaseRegistry());
        return database;
    }

    private void enrichDatabaseEntity(DatabaseRegistry databaseRegistry, CreatedDatabaseV3 createdDatabase, FunctionProvidePassword<Database, String> password, String role) {
        log.debug("enrich database = {} by createdDatabase = {}", databaseRegistry, createdDatabase);
        databaseRegistry.getDatabase().setAdapterId(createdDatabase.getAdapterId());
        databaseRegistry.getDatabase().setConnectionProperties(Optional.ofNullable(createdDatabase.getConnectionProperties())
                .orElseThrow(EmptyConnectionPropertiesException::new));
        ConnectionPropertiesUtils.getConnectionProperties(databaseRegistry.getDatabase().getConnectionProperties(), role)
                .put(PASSWORD_FIELD, password.apply(databaseRegistry.getDatabase(), role));
        databaseRegistry.getDatabase().setResources(createdDatabase.getResources());
        databaseRegistry.getDatabase().setName(createdDatabase.getName());
        Date timeDbCreation = new Date();
        databaseRegistry.getDatabase().setTimeDbCreation(timeDbCreation);
        databaseRegistry.setTimeDbCreation(timeDbCreation);
        databaseRegistry.getDatabase().setConnectionDescription(createdDatabase.getConnectionDescription());
        databaseRegistry.getDatabase().setPhysicalDatabaseId(physicalDatabasesService.getByAdapterId(createdDatabase.getAdapterId()).getPhysicalDatabaseIdentifier());
        databaseRegistry.getDatabase().getDbState().setDatabaseState(DbState.DatabaseStateStatus.CREATED);
        databaseRegistry.getDbState().setPodName(null);
    }

    private TreeMap<String, Object> classifierFromCreateRequest(AbstractDatabaseCreateRequest createRequest, String namespace) {
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.putAll(createRequest.getClassifier());
        classifier.put("namespace", namespace);
        return classifier;
    }

    private void preResponseProcessing(DatabaseRegistry databaseRegistry, FunctionProvidePassword<Database, String> password) {
        // We set id in null because we send database id only once when database had been created
        databaseRegistry.setId(null);
        databaseRegistry.getDatabase().setId(null);
        if (databaseRegistry.getDatabase().getConnectionProperties() == null || databaseRegistry.getDatabase().getConnectionProperties().isEmpty()
                || databaseRegistry.getDatabase().getConnectionProperties().get(0).isEmpty()) {
            Map<String, Object> anotherMap = new HashMap<>();
            anotherMap.put(ROLE, Role.ADMIN.toString());
            databaseRegistry.getDatabase().setConnectionProperties(Arrays.asList(anotherMap));
        }
        dBaaService.getConnectionPropertiesService().addAdditionalPropToCP(databaseRegistry);
        log.debug("Database connection properties = {}", databaseRegistry.getDatabase().getConnectionProperties());
        databaseRegistry.getDatabase().getConnectionProperties().forEach(v -> v.put(PASSWORD_FIELD,
                password.apply(databaseRegistry.getDatabase(), (String) v.get(ROLE))));
    }


    private void deleteDatabase(DatabaseRegistry databaseRegistry, CreatedDatabaseV3 createdDatabase) {
        try {
            // check if db was created by adapter
            if (createdDatabase != null && createdDatabase.getResources() != null) {
                log.info("Rollback creation of database {}", createdDatabase.getName());
                dBaaService.dropDatabase(databaseRegistry);
                log.info("Database resources: " + createdDatabase.getResources() + " were dropped via roll back operation");
            }
            log.info("Rollback creation of registry {}", databaseRegistry.getId());
            databaseRegistryDbaasRepository.delete(databaseRegistry);
        } catch (Exception dropDbException) {
            log.error("Failed to drop db as roll back action for create operation", dropDbException);
        }
    }


    public void updateDatabase(DatabaseRegistry databaseRegistry, AbstractDatabaseCreateRequest createRequest) {
        logicalDbSettingsService.updateSettings(databaseRegistry, createRequest.getSettings());
    }

    private Response getResponseEntity(AbstractDatabaseCreateRequest createRequest, FunctionProvidePassword<Database, String> password,
                                       TreeMap<String, Object> classifier, RuntimeException ex, boolean isStorageAvailable) {
        DatabaseRegistry databaseRegistry = dBaaService.findDatabaseByClassifierAndType(classifier, createRequest.getType(), true);
        if (databaseRegistry != null) {
            if (dBaaService.isModifiedFields(createRequest, databaseRegistry.getDatabase())) {
                return Response.status(Response.Status.BAD_REQUEST).entity(("Database with such classifier already exists, but unmodified fields (backupDisabled) can not be modified")).build();
            }
            try {
                if (isStorageAvailable) {
                    updateDatabase(databaseRegistry, createRequest);
                }
            } catch (WebApplicationException e) {
                log.error(MESSAGE_ERROR_DURING_UPDATE_DATABASE, classifier, createRequest.getType());
                Response.StatusType statusInfo = e.getResponse().getStatusInfo();
                return Response.status(statusInfo).entity(String.format("Updating database failed with error: %s %s, message: %s", statusInfo.getStatusCode(), statusInfo.getReasonPhrase(), e.getResponse().getEntity())).build();
            } catch (Exception e) {
                log.error(MESSAGE_ERROR_DURING_UPDATE_DATABASE, classifier, createRequest.getType());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Updating database failed with error: " + e.getMessage()).build();
            }
            // We set id in null because we send database id only once when database had been created
            databaseRegistry.setId(null);
            databaseRegistry.getDatabase().setId(null);
            log.info("Skip database creation. Requested to create already registered {} database: {}", createRequest.getType(), classifier);
            if (databaseRegistry.getDatabase().getConnectionProperties() == null) {
                Map<String, Object> anotherMap = new HashMap<>();
                anotherMap.put(ROLE, Role.ADMIN.toString());
                databaseRegistry.getDatabase().setConnectionProperties(Arrays.asList(anotherMap));
            }
            dBaaService.getConnectionPropertiesService().addAdditionalPropToCP(databaseRegistry);
            ConnectionPropertiesUtils.getConnectionProperties(databaseRegistry.getDatabase().getConnectionProperties(),
                    Role.ADMIN.toString()).put(PASSWORD_FIELD, password.apply(databaseRegistry.getDatabase(), Role.ADMIN.toString()));
            // database was requested but not created yet
            if (DbState.DatabaseStateStatus.PROCESSING.equals(databaseRegistry.getDatabase().getDbState().getDatabaseState())) {
                log.debug("Database {} is not created yet", classifier);
                return Response.accepted(dBaaService.processConnectionProperties(databaseRegistry)).build();
            }
            return Response.ok(dBaaService.processConnectionProperties(databaseRegistry)).build();
        } else {
            log.error("Duplicate database of type {} was not found, classifier: {}", createRequest.getType(), classifier, ex);
            return Response.status(Response.Status.CONFLICT).entity("Already has such database.").build();
        }
    }


    public static class AggregatedDatabaseAdministrationServiceConst {
        public static final String NO_ADAPTER_MSG = "There is no appropriate adapter for the specified database type";
        public static final String ROLE_IS_NOT_ALLOWED = "Requested role is not allowed";
        public static final String ACCESS_NAMESPACE_FORBIDDEN = "You cannot access databases in this namespace";

        private AggregatedDatabaseAdministrationServiceConst() {
        }
    }

    public static class AggregatedDatabaseAdministrationUtils {

        public static boolean isUniqueViolation(Exception ex) {
            Throwable subException = ex;
            String sqlState = null;
            while (subException.getCause() != null) {
                subException = subException.getCause();
                if (subException instanceof SQLException) {
                    String currentSqlState = ((SQLException) subException).getSQLState();
                    if (currentSqlState != null) {
                        sqlState = currentSqlState;
                    }
                }
            }
            return UNIQUE_VIOLATION.getState().equals(sqlState);
        }

        public static Response responseDatabaseCreated(DatabaseRegistry databaseRegistry, String physicalDatabaseId, String role) {
            return createResponseDatabaseCreated(new DatabaseResponseV3SingleCP(databaseRegistry, physicalDatabaseId, role));
        }

        public static boolean isClassifierCorrect(Map<String, Object> classifier) {
            if (classifier != null && classifier.containsKey(MICROSERVICE_NAME) && classifier.containsKey(NAMESPACE)) {
                return Objects.equals(classifier.get(SCOPE), SCOPE_VALUE_SERVICE) || (Objects.equals(classifier.get(SCOPE), SCOPE_VALUE_TENANT) && classifier.containsKey(TENANT_ID));
            }
            return false;
        }

        private static Response createResponseDatabaseCreated(DatabaseResponseV3 response) {
            return Response
                    .status(Response.Status.CREATED)
                    .entity(response).build();
        }
    }
}
