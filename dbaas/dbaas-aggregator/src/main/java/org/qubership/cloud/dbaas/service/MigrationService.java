package org.qubership.cloud.dbaas.service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.qubership.cloud.dbaas.dto.API_VERSION;
import org.qubership.cloud.dbaas.dto.EnsuredUser;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.migration.RegisterDatabaseResponseBuilder;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.dto.v3.RegisterDatabaseRequestV3;
import org.qubership.cloud.dbaas.exceptions.DbNotFoundException;
import org.qubership.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.exception.ConstraintViolationException;

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.service.PasswordEncryption.PASSWORD_FIELD;

@ApplicationScoped
@Slf4j
public class MigrationService {

    @Inject
    private PhysicalDatabasesService physicalDatabasesService;

    @Inject
    private DBaaService dBaaService;

    public RegisterDatabaseResponseBuilder registerDatabases(List<RegisterDatabaseRequestV3> databasesToRegister,
                                                             API_VERSION version, Boolean isUserCreation) {
        log.info("Start database migration, register {} databases", databasesToRegister.size());
        RegisterDatabaseResponseBuilder responseBuilder = new RegisterDatabaseResponseBuilder();

        final List<RegisterDatabaseRequestV3> withAdapterId = Lists.newArrayList();
        final List<RegisterDatabaseRequestV3> withPhydbId = Lists.newArrayList();
        final List<RegisterDatabaseRequestV3> withoutAdapterId = Lists.newArrayList();
        final List<RegisterDatabaseRequestV3> toValidate = Lists.newArrayList();

        databasesToRegister.forEach(request -> {
            DatabaseRegistry existingDatabase = findExistingDatabase(version, request);
            if (existingDatabase != null && !existingDatabase.isExternallyManageable()) {
                defineDuplicateStatus(responseBuilder, request);
                return;
            }
            String adapterId = request.getAdapterId();
            String physicalDatabaseId = request.getPhysicalDatabaseId();
            if (StringUtils.isBlank(physicalDatabaseId) && StringUtils.isNotBlank(request.getDbHost())) {
                physicalDatabaseId = findPhysicalDbIdByDbHostAndDbName(request.getDbHost(), request.getName());
                request.setPhysicalDatabaseId(physicalDatabaseId);
                log.info("Using physical database with id={} for databases registration", physicalDatabaseId);
            }
            if (!StringUtils.isEmpty(adapterId) && !StringUtils.isEmpty(physicalDatabaseId)) {
                log.info("Should validate request {}", request);
                toValidate.add(request);
            } else if (!StringUtils.isEmpty(adapterId)) {
                log.info("Request with only adapterId: {}", request);
                withAdapterId.add(request);
            } else if (!StringUtils.isEmpty(physicalDatabaseId)) {
                log.info("Request with only physicalDatabaseId: {}", request);
                withPhydbId.add(request);
            } else {
                log.info("Request without adapterId and physicalDatabaseIdentifier: {}", request);
                withoutAdapterId.add(request);
            }
        });

        // We will use adapterId to migrate databases, so need to resolve adapterIds and validate all requests before
        // migrating. Collection validatedRequests will be used to hold requests with resolved and validated adapterIds.
        final List<RegisterDatabaseRequestV3> validatedRequests = Lists.newArrayList();

        if (!withAdapterId.isEmpty()) {
            validatedRequests.addAll(validateAdapterIds(responseBuilder, withAdapterId));
        }
        if (!withPhydbId.isEmpty()) {
            validatedRequests.addAll(fillAdapterIdByPhysicalDatabaseIdentifier(responseBuilder, withPhydbId));
        }
        if (!toValidate.isEmpty()) {
            validatedRequests.addAll(validateFullRequests(responseBuilder, toValidate));
        }

        // Collect existing database names for all adapters
        Map<String, Optional<Collection<String>>> databasesNamesPerAdapter = collectDatabasesPerAdapters(validatedRequests, withoutAdapterId);

        if (!withoutAdapterId.isEmpty()) {
            validatedRequests.addAll(resolveRequestsWithoutAdapterId(responseBuilder, withoutAdapterId, databasesNamesPerAdapter));
        }
        // Register databases with resolved adapterIds
        validatedRequests.forEach(request -> registerDatabaseWithAdapterId(responseBuilder, request, databasesNamesPerAdapter, version, isUserCreation));
        return responseBuilder;
    }

    private DatabaseRegistry findExistingDatabase(API_VERSION version, RegisterDatabaseRequestV3 request) {
        DatabaseRegistry databaseRegistry;
        if (API_VERSION.V3.equals(version)) {
            databaseRegistry = dBaaService.findDatabaseByClassifierAndType(request.getClassifier(), request.getType(), true);
        } else {
            databaseRegistry = dBaaService.findDatabaseByOldClassifierAndType(request.getClassifier(), request.getType(), true);
        }
        return databaseRegistry;
    }

    private String findPhysicalDbIdByDbHostAndDbName(String dbHost, String dbName) {
        String adapterNamespace = dbHost.substring(dbHost.indexOf('.') + 1);
        List<PhysicalDatabase> physicalDatabaseByAdapterHost = physicalDatabasesService.getPhysicalDatabaseByAdapterHost(adapterNamespace);
        log.info("Found {} physical databases with address={}", physicalDatabaseByAdapterHost.size(), dbHost);
        if (physicalDatabaseByAdapterHost.isEmpty()) {
            throw new UnregisteredPhysicalDatabaseException(String.format("Physical database with host=%s is not registered", dbHost));
        } else if (physicalDatabaseByAdapterHost.size() == 1) {
            return physicalDatabaseByAdapterHost.get(0).getPhysicalDatabaseIdentifier();
        } else {
            for (PhysicalDatabase pd : physicalDatabaseByAdapterHost) {
                if (isPhysicalDbContainsDbName(pd, dbName)) {
                    return pd.getPhysicalDatabaseIdentifier();
                }
                log.debug("Physical database with id={} doesn't contain logical database with name={}", pd.getPhysicalDatabaseIdentifier(), dbName);
            }
            throw new DbNotFoundException(String.format("Could not find logical database %s in physical database with adapter address %s", dbName, dbHost), Source.builder().build());
        }
    }

    private boolean isPhysicalDbContainsDbName(PhysicalDatabase pd, String dbName) {
        Optional<Collection<String>> registeredDatabases = getRegisteredDatabases(physicalDatabasesService.getAdapterById(pd.getAdapter().getAdapterId()));
        return registeredDatabases.map(names -> names.stream().anyMatch(name -> name.equals(dbName))).orElse(false);
    }

    private Map<String, Optional<Collection<String>>> collectDatabasesPerAdapters(List<RegisterDatabaseRequestV3> withAdapterId,
                                                                                  List<RegisterDatabaseRequestV3> withoutAdapterId) {
        Map<String, Optional<Collection<String>>> databasesNamesPerAdapter = Maps.newHashMap();
        // If there are no requests with unresolved adapterId, we need to check only required adapters
        if (withoutAdapterId.isEmpty()) {
            withAdapterId.forEach(request -> {
                databasesNamesPerAdapter.computeIfAbsent(request.getAdapterId(),
                        adapterId -> getRegisteredDatabases(physicalDatabasesService.getAdapterById(adapterId)));
            });
        } else { // otherwise need to check all adapters
            List<DbaasAdapter> allAdapters = physicalDatabasesService.getAllAdapters();
            allAdapters.forEach(dbaasAdapter ->
                    databasesNamesPerAdapter.put(dbaasAdapter.identifier(), getRegisteredDatabases(dbaasAdapter))
            );
        }
        return databasesNamesPerAdapter;
    }

    private Optional<Collection<String>> getRegisteredDatabases(DbaasAdapter dbaasAdapter) {
        Collection<String> databases;
        try {
            log.info("Get all database from dbaas adapter with identifier {}", dbaasAdapter.identifier());
            databases = dbaasAdapter.getDatabases();
        } catch (WebApplicationException e) {
            log.error("Request to adapter {} was not processed with code {}", dbaasAdapter, e.getResponse().getStatus());
            return Optional.empty();
        }
        return Optional.ofNullable(databases);
    }

    /**
     * Registers database from request containing non-null {@code adapterId}.
     *
     * @param responseBuilder       builder for the migration HTTP response
     * @param requestsWithAdapterId request with non-null {@code adapterId}
     * @param databasesPerAdapter   map of database names per adapter: key - adapterId;
     *                              value - optional with collection of names of the databases, which present in
     *                              this adapter. Empty optional means that adapter version is too old
     *                              and database existence validation can be skipped
     * @param isUserCreation
     */
    private void registerDatabaseWithAdapterId(RegisterDatabaseResponseBuilder responseBuilder,
                                               RegisterDatabaseRequestV3 requestsWithAdapterId,
                                               Map<String, Optional<Collection<String>>> databasesPerAdapter,
                                               API_VERSION version,
                                               boolean isUserCreation) {
        assert requestsWithAdapterId.getAdapterId() != null;

        String dbName = requestsWithAdapterId.getName();
        try {
            log.info("Register database {} in adapter with id {} and physical id {}", dbName, requestsWithAdapterId.getAdapterId(), requestsWithAdapterId.getPhysicalDatabaseId());
            Optional<Collection<String>> dbsInAdapter = databasesPerAdapter.get(requestsWithAdapterId.getAdapterId());
            if (!dbsInAdapter.isPresent() || !dbsInAdapter.get().contains(dbName)) {
                log.info("Database {} is not found at specified adapter databases collection, maybe old " +
                        "version of adapter is used", dbName);
                if (dbsInAdapter.isPresent()) {
                    log.error("Validation is enabled, db cannot be registered as it absents in adapter databases list");
                    responseBuilder.addFailedDb(dbName, requestsWithAdapterId.getType());
                    responseBuilder.addFailureReason(dbName, requestsWithAdapterId.getType(), "Could not find " +
                            "registered database by adapter " + requestsWithAdapterId.getAdapterId() + " and physical id " + requestsWithAdapterId.getPhysicalDatabaseId());
                    return;
                }
            }
            DatabaseRegistry existingDatabase = findExistingDatabase(version, requestsWithAdapterId);
            boolean isProcessExternalAsInternal = existingDatabase != null && existingDatabase.isExternallyManageable();
            if (isProcessExternalAsInternal) {
                existingDatabase.setExternallyManageable(false);
                existingDatabase.setBackupDisabled(requestsWithAdapterId.getBackupDisabled());
                existingDatabase.setPhysicalDatabaseId(requestsWithAdapterId.getPhysicalDatabaseId());
                existingDatabase.setAdapterId(requestsWithAdapterId.getAdapterId());
            }
            DatabaseRegistry db = isProcessExternalAsInternal ?
                    existingDatabase :
                    newDatabaseByRequest(requestsWithAdapterId);
            db.setResources(new ArrayList<>());
            if (isUserCreation) {
                recreateUserResources(dbName, db);
            }
            db.getResources().add(new DbResource(DbResource.DATABASE_KIND, db.getName()));
            List<Object> passwords = db.getConnectionProperties().stream().
                    map(properties -> properties.getOrDefault(PASSWORD_FIELD, null)).collect(Collectors.toList());
            if (passwords.contains(null) || passwords.contains("")) {
                responseBuilder.addFailedDb(dbName, requestsWithAdapterId.getType());
                responseBuilder.addFailureReason(dbName, requestsWithAdapterId.getType(), "No password for not " +
                        "database not existing in dbaas");
                log.error("Got db {} without password and this db doesn't exist in the database of DBAAS", requestsWithAdapterId);
                return;
            }

            dBaaService.encryptAndSaveDatabaseEntity(db);
            db.getDatabaseRegistry().forEach(dbr -> responseBuilder.addMigratedDb(dBaaService.processConnectionPropertiesV3(dbr)));
        } catch (ConstraintViolationException ex) {
            defineDuplicateStatus(responseBuilder, requestsWithAdapterId);
        } catch (Exception ex) {
            responseBuilder.addFailedDb(dbName, requestsWithAdapterId.getType());
            log.error("Failed to register database {} during migration with classifier {}, " +
                    "skip migration for this database.", dbName, requestsWithAdapterId.getClassifier(), ex);
        }
    }

    private void recreateUserResources(String dbName, DatabaseRegistry dbRegistry) {
        PhysicalDatabase physicalDb = physicalDatabasesService.getByAdapterId(dbRegistry.getAdapterId());
        physicalDatabasesService.getAdapterById(physicalDb.getAdapter().getAdapterId())
                .changeMetaData(dbRegistry.getName(), AbstractDbaasAdapterRESTClient.buildMetadata(dbRegistry.getClassifier()));
        List<String> userRoles = physicalDb.getRoles();
        List<EnsuredUser> ensuredUsers = userRoles.stream().map(role -> dBaaService.recreateUsers(physicalDatabasesService.getAdapterById(dbRegistry.getAdapterId()), null, dbName, null, role))
                .collect(Collectors.toList());
        dbRegistry.setConnectionProperties(new ArrayList<>());
        for (EnsuredUser ensuredUser : ensuredUsers) {
            dbRegistry.getConnectionProperties().add(ensuredUser.getConnectionProperties());
            dbRegistry.getResources().addAll(ensuredUser.getResources());
        }
    }

    /**
     * Validates requests with both {@code physicalDatabaseId} and {@code adapterId} specified. Checks whether these
     * {@code physicalDatabaseId} and {@code adapterId} belong to the same physical database. If no, adds
     * failed database to the {@code responseBuilder}.
     *
     * @param responseBuilder builder for the migration HTTP response
     * @param toValidate      list of requests with both {@code physicalDatabaseId} and {@code adapterId}.
     * @return list of validated requests
     */
    private List<RegisterDatabaseRequestV3> validateFullRequests(RegisterDatabaseResponseBuilder responseBuilder,
                                                                 List<RegisterDatabaseRequestV3> toValidate) {
        List<RegisterDatabaseRequestV3> withAdapterId = new ArrayList<>(toValidate.size());
        for (RegisterDatabaseRequestV3 request : toValidate) {
            log.debug("Validating request {}", request);
            String physicalDatabaseId = request.getPhysicalDatabaseId();
            PhysicalDatabase physicalDatabase =
                    physicalDatabasesService.getByPhysicalDatabaseIdentifier(physicalDatabaseId);
            String adapterId = request.getAdapterId();
            if (physicalDatabase == null ||
                    !physicalDatabase.getAdapter().getAdapterId().equalsIgnoreCase(adapterId)) {
                log.error("Validation failed for phydbid = {} and adapterId = {}", physicalDatabaseId, adapterId);
                String dbName = request.getName();
                String dbType = request.getType();
                responseBuilder.addFailedDb(dbName, dbType);
                responseBuilder.addFailureReason(dbName, dbType, "Set adapterId " + adapterId +
                        " conflicts with set physicalDatabaseIdentifier " + physicalDatabaseId);
                continue;
            }
            withAdapterId.add(request);
        }
        return withAdapterId;
    }

    /**
     * Validates requests with specified {@code adapterId}s. Checks whether adapters with such ids are registered in
     * dbaas-aggregator. If no, adds failure to HTTP {@code responseBuilder}.
     *
     * @param responseBuilder builder for the migration HTTP response
     * @param withAdapterId   list of requests with {@code adapterId}, which need to be validated
     * @return list of requests with {@code adapterIds}, for which registered adapters were found successfully
     */
    private List<RegisterDatabaseRequestV3> validateAdapterIds(RegisterDatabaseResponseBuilder responseBuilder,
                                                               List<RegisterDatabaseRequestV3> withAdapterId) {
        List<RegisterDatabaseRequestV3> validatedRequests = new ArrayList<>(withAdapterId.size());
        for (RegisterDatabaseRequestV3 request : withAdapterId) {
            log.debug("Validating request {}", request);
            try {
                DbaasAdapter adapter = physicalDatabasesService.getAdapterById(request.getAdapterId());
                if (adapter == null) {
                    log.error("Couldn't find registered adapter with id {}", request.getAdapterId());
                    String dbName = request.getName();
                    String dbType = request.getType();
                    responseBuilder.addFailedDb(dbName, dbType);
                    responseBuilder.addFailureReason(dbName, dbType, "There is no registered adapter with id " + request.getAdapterId());
                } else {
                    request.setPhysicalDatabaseId(physicalDatabasesService.getByAdapterId(request.getAdapterId()).getPhysicalDatabaseIdentifier());
                    validatedRequests.add(request);
                }
            } catch (UnregisteredPhysicalDatabaseException e) {
                log.error("Couldn't find registered adapter with id {}!", request.getAdapterId(), e);
                String dbName = request.getName();
                String dbType = request.getType();
                responseBuilder.addFailedDb(dbName, dbType);
                responseBuilder.addFailureReason(dbName, dbType, "There is no registered adapter with id " + request.getAdapterId());
            }
        }
        return validatedRequests;
    }

    /**
     * Resolves {@code adapterId} field for the requests which contain {@code physicalDatabaseId},
     * but not {@code adapterId}. Updates {@code responseBuilder} in case of errors.
     *
     * @param responseBuilder builder for the migration HTTP response
     * @param withPhydbId     list of requests containing {@code physicalDatabaseId}, but not {@code adapterId}
     * @return list of requests with resolved adapter ids
     */
    private List<RegisterDatabaseRequestV3> fillAdapterIdByPhysicalDatabaseIdentifier(RegisterDatabaseResponseBuilder responseBuilder,
                                                                                      List<RegisterDatabaseRequestV3> withPhydbId) {
        List<RegisterDatabaseRequestV3> withAdapterId = new ArrayList<>(withPhydbId.size());
        for (RegisterDatabaseRequestV3 request : withPhydbId) {
            log.debug("Processing request {}", request);
            String physicalDatabaseId = request.getPhysicalDatabaseId();
            PhysicalDatabase physicalDatabase =
                    physicalDatabasesService.getByPhysicalDatabaseIdentifier(physicalDatabaseId);
            if (physicalDatabase == null) {
                log.error("Could not find physical database with id = {}", physicalDatabaseId);
                String dbName = request.getName();
                String dbType = request.getType();
                responseBuilder.addFailedDb(dbName, dbType);
                responseBuilder.addFailureReason(dbName, dbType, "Physical database " +
                        physicalDatabaseId + " is not registered");
                continue;
            }
            String adapterId = physicalDatabase.getAdapter().getAdapterId();
            log.info("Found physical database {}, adapterId = {}", physicalDatabase, adapterId);
            request.setAdapterId(adapterId);
            withAdapterId.add(request);
        }
        return withAdapterId;
    }

    private void defineDuplicateStatus(RegisterDatabaseResponseBuilder response, RegisterDatabaseRequestV3 reg) {
        DatabaseRegistry existingDatabase = dBaaService.findDatabaseByClassifierAndType(reg.getClassifier(), reg.getType(), false);
        if (Objects.equals(existingDatabase.getDatabase().getName(), reg.getName())) {
            response.addMigratedDb(dBaaService.processConnectionPropertiesV3(existingDatabase));
            log.info("Got full duplicate with name {} and classifier {} and type {}, skip migration for this database.",
                    existingDatabase.getDatabase().getName(), reg.getClassifier(), reg.getType());
        } else {
            response.addConflictedDb(reg.getName(), reg.getType());
            response.addFailureReason(reg.getName(), reg.getType(), "duplicate database with different " +
                    "parameters already registered");
            log.error("Got duplicate while database migration for classifier {}, and type {} " +
                            "and with different name - expected {} but got {}. " +
                            "Skip migration for this database.", reg.getClassifier(), reg.getType(), reg.getName(),
                    existingDatabase.getDatabase().getName());
        }
    }

    /**
     * Resolves {@code adapterId}s for requests with no {@code adapterId} and {@code physicalDatabaseId} specified.
     *
     * @param responseBuilder     builder for the migration HTTP response
     * @param withoutAdapterId    list of requests containing no {@code adapterId} and no {@code physicalDatabaseId}
     * @param databasesPerAdapter map of database names per adapter: key - adapterId;
     *                            value - optional with collection of names of the databases, which present in
     *                            this adapter
     * @return list of requests with resolved {@code adapterId}s
     */
    private List<RegisterDatabaseRequestV3> resolveRequestsWithoutAdapterId(RegisterDatabaseResponseBuilder responseBuilder,
                                                                            List<RegisterDatabaseRequestV3> withoutAdapterId,
                                                                            Map<String, Optional<Collection<String>>> databasesPerAdapter) {
        List<RegisterDatabaseRequestV3> resolvedRequests = new ArrayList<>(withoutAdapterId.size());
        withoutAdapterId.forEach(request -> {
            List<String> potentialAdapters = Lists.newArrayList();
            String dbName = request.getName();
            databasesPerAdapter.forEach((adapterId, databases) -> {
                if (databases.isPresent() && databases.get().contains(dbName)
                        && physicalDatabasesService.getAdapterById(adapterId).type().equals(request.getType())) {
                    potentialAdapters.add(adapterId);
                }
            });
            if (potentialAdapters.isEmpty()) {
                log.error("Failed to register database {}, couldn't find it in any physical database of type {}", request.getName(), request.getType());
                responseBuilder.addFailedDb(request.getName(), request.getType());
                responseBuilder.addFailureReason(request.getName(), request.getType(), "Database " +
                        request.getName() + " cannot be found in any of physical databases " + "of type " +
                        request.getType());
            } else if (potentialAdapters.size() == 1) {
                String adapterId = potentialAdapters.get(0);
                log.debug("Resolved adapterId {} for request {}", adapterId, request);
                request.setAdapterId(adapterId);
                request.setPhysicalDatabaseId(physicalDatabasesService.getByAdapterId(adapterId).getPhysicalDatabaseIdentifier());
                resolvedRequests.add(request);
            } else {
                log.error("Cannot resolve database {} as {} potential candidates are found",
                        dbName, potentialAdapters.size());
                String dbType = request.getType();
                responseBuilder.addConflictedDb(dbName, dbType);
                responseBuilder.addFailureReason(dbName, dbType, "Cannot resolve adapter uniquely for " +
                        "database without set adapterId or physicalDatabaseIdentifier");
            }
        });
        return resolvedRequests;
    }

    private DatabaseRegistry newDatabaseByRequest(RegisterDatabaseRequestV3 request) {
        Database database = new Database(request);

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(request.getClassifier());
        databaseRegistry.setType(request.getType());
        databaseRegistry.setNamespace(request.getNamespace());
        databaseRegistry.setTimeDbCreation(database.getTimeDbCreation());
        databaseRegistry.setDatabase(database);
        return databaseRegistry;
    }
}