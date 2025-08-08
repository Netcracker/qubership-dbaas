package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.PasswordChangeRequestV3;
import org.qubership.cloud.dbaas.dto.v3.UpdateClassifierRequestV3;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.service.*;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.DbaasApiPath.DATABASE_OPERATION_PATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static org.qubership.cloud.dbaas.service.AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationServiceConst.ROLE_IS_NOT_ALLOWED;

@Slf4j
@Path(DATABASE_OPERATION_PATH_V3)
@Tag(name = "Database operation controller v3",
        description = "This controller contains API for operations with already created databases, users.")
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseOperationControllerV3 {

    @Inject
    DBaaService dBaaService;
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @Inject
    BlueGreenService blueGreenService;
    @Inject
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @Inject
    OperationService operationService;
    @Inject
    EncryptionServiceProvider encryptionServiceProvider;

    @Operation(summary = "V3. Change user password",
            description = "The API changes password of a user that is related to the specified database. A password will be changed to a random value." +
                    "If classifier is not passed then all passwords of databases in the namespace and type will be changed.")
    @APIResponses({
            @APIResponse(responseCode = "401", description = ROLE_IS_NOT_ALLOWED, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "200", description = "The passwords have been changed successfully. " +
                    "If errors will occur during the password changes, then the errors are aggregated and returned with maximum error status",
                    content = @Content(schema = @Schema(implementation = PasswordChangeResponse.class)))
    })
    @Path("/password-changes")
    @POST
    @RolesAllowed(DB_CLIENT)
    @Transactional
    public Response changeUserPassword(@Parameter(description = "Describes the database and the type of database that needs a password to be changed", required = true)
                                       PasswordChangeRequestV3 passwordChangeRequest,
                                       @Parameter(description = "Project namespace in which the databases are used")
                                       @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Received request on changed password with request body {} and namespace {}", passwordChangeRequest, namespace);
        if (passwordChangeRequest == null || StringUtils.isEmpty(passwordChangeRequest.getType())) {
            throw new PasswordChangeValidationException("The request body is empty or database type is not specified", Source.builder()
                    .pointer(passwordChangeRequest == null ? "/" : "/type").build());
        }
        PasswordChangeResponse response;
        if (passwordChangeRequest.getUserRole() != null) {
            response = dBaaService.changeUserPassword(passwordChangeRequest, namespace, passwordChangeRequest.getUserRole());
        } else {
            response = dBaaService.changeUserPassword(passwordChangeRequest, namespace);
        }

        log.info("Result of password change request {}", response);
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Recreate database with existing classifier. Prohibited for Blue-Green",
            description = "Recreate existing database with same classifier in the same physicalDb or in another. " +
                    "The API can be useful if you want to migrate associated with microservice logical db to another physical database. So, " +
                    "DBaaS creates a new empty database. After it, you will get a new connection and can perform a migration." +
                    "Pay attention, each request will produce a new database even if the database was previously recreated. " +
                    "So, if your response contains unsuccessful databases you must leave only these databases in the request. " +
                    "Otherwise successful databases will be recreated again. The previous database is not deleted but is marked as archived.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "All requested databases were recreated. There are no unsuccessful", content = @Content(schema = @Schema(implementation = RecreateDatabaseResponse.class))),
            @APIResponse(responseCode = "500", description = "Some requested databases were not recreated. There are unsuccessful", content = @Content(schema = @Schema(implementation = RecreateDatabaseResponse.class))),
            @APIResponse(responseCode = "400", description = "Request does not pass validation. Maybe passed physical databases id is unregistered " +
                    "or logical database with requested classifier has not been created before.", content = @Content(schema = @Schema(implementation = String.class)))
    })
    @Path("/databases/recreate")
    @POST
    @RolesAllowed(DB_EDITOR)
    @Transactional
    public Response recreateDatabases(@Parameter(description = "namespace where microservices live that associated with these databases", required = true)
                                      @PathParam(NAMESPACE_PARAMETER) String namespace,
                                      @Parameter(description = "Request body must contain registered physicalDatabaseId " +
                                              "and classifier of created logical database. " +
                                              "The list of created databases can be found by 'List of all databases' API", required = true)
                                      List<RecreateDatabaseRequest> recreateDatabasesRequests) {
        log.info("Get request to recreate existing databases. Request body {}", recreateDatabasesRequests);
        // request validation
        if (blueGreenService.getBgDomainContains(namespace).isPresent()) {
            log.info("recreate operation is prohibited in blue-green");
            throw new RuntimeException("Operation not supports in blue-green");
        }
        List<ValidationException> errors = validateRecreateDbRequest(namespace, recreateDatabasesRequests);
        if (!errors.isEmpty()) {
            throw new MultiValidationException(errors);
        }
        // request is correct. Try to recreate databases.
        RecreateDatabaseResponse response = new RecreateDatabaseResponse();
        for (RecreateDatabaseRequest recreateDbRequest : recreateDatabasesRequests) {
            try {
                Optional<DatabaseRegistry> existedDbRegisty = getDatabase(namespace, recreateDbRequest);
                DatabaseRegistry newDb = dBaaService.recreateDatabase(existedDbRegisty.orElseThrow(), recreateDbRequest.getPhysicalDatabaseId());
                log.info("logical database with classifier {} and type {} was successfully recreated in physiacalDb id {}",
                        recreateDbRequest.getClassifier(), recreateDbRequest.getType(), recreateDbRequest.getPhysicalDatabaseId());
                DatabaseResponse newDbResponse = new DatabaseResponse(newDb.getDatabaseRegistry().get(0));// Here we can call get(0) beacuse recreate available only with 1 classifier
                newDbResponse.setClassifier(newDb.getClassifier());
                response.dbRecreated(recreateDbRequest.getClassifier(), recreateDbRequest.getType(), newDbResponse);
            } catch (Exception ex) {
                log.error("Logical database with classifier {} and type {} can't be recreated in physical db {}. Error: {}", recreateDbRequest.getClassifier(),
                        recreateDbRequest.getType(), recreateDbRequest.getPhysicalDatabaseId(), ex.getMessage());
                response.dbNotRecreated(recreateDbRequest.getClassifier(), recreateDbRequest.getType(), ex.getMessage());
            }
        }
        if (!response.getUnsuccessfully().isEmpty()) {
            throw new RecreateDbFailedException(namespace, response);
        }
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Update existing database classifier",
            description = "The API allows to update existing database classifier")
    @APIResponses({
            @APIResponse(responseCode = "400", description = "\"from\" or \"to\" classifiers must not be empty", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "406", description = "\"from\" or \"to\" classifiers contain namespace different from in the path", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "401", description = ROLE_IS_NOT_ALLOWED, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "409", description = "There is a database with provided \"to\" classifier or ", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "There is no database with provided \"from\" classifier", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "200", description = "The database classifier was updated successfully", content = @Content(schema = @Schema(implementation = Database.class)))
    })
    @Path("/databases/update-classifier/{type}")
    @PUT
    @RolesAllowed({DB_EDITOR, DB_CLIENT})
    @Transactional
    public Response updateClassifier(@Parameter(description = "Project namespace in which the databases are used", required = true)
                                     @PathParam(NAMESPACE_PARAMETER) String namespace,
                                     @Parameter(description = "Type of physical database where database was created, e.g. mongodb, postgresql", required = true)
                                     @PathParam("type") String type,
                                     @Parameter(description = "Contains primary and target classifier", required = true)
                                     UpdateClassifierRequestV3 updateClassifierRequest) {
        log.info("Get request on update database classifier from {} to {} with type={} and namespace={}", updateClassifierRequest.getFrom(),
                updateClassifierRequest.getTo(), type, namespace);
        List<ValidationException> errors = new ArrayList<>();
        if (MapUtils.isEmpty(updateClassifierRequest.getFrom())) {
            errors.add(new InvalidClassifierException("Classifier 'from' cannot be empty", updateClassifierRequest.getFrom(),
                    Source.builder().pointer("/from").build()));
        }
        if (MapUtils.isEmpty(updateClassifierRequest.getTo())) {
            errors.add(new InvalidClassifierException("Classifier 'to' cannot be empty", updateClassifierRequest.getTo(),
                    Source.builder().pointer("/to").build()));
        }
        if (!checkClassifier(updateClassifierRequest.getFrom(), namespace)) {
            errors.add(new InvalidClassifierException(String.format("Classifier 'from' contains namespace '%s' which is empty or not equal to requested namespace '%s'",
                    Optional.ofNullable(updateClassifierRequest.getFrom()).map(c -> c.get(NAMESPACE)).orElse(null), namespace), updateClassifierRequest.getFrom(),
                    Source.builder().pointer("/from/namespace").build()));
        }
        if (!checkClassifier(updateClassifierRequest.getTo(), namespace)) {
            errors.add(new InvalidClassifierException(String.format("Classifier 'to' contains namespace '%s' which is not equal to requested namespace '%s'",
                    Optional.ofNullable(updateClassifierRequest.getTo()).map(c -> c.get(NAMESPACE)).orElse(null), namespace), updateClassifierRequest.getTo(),
                    Source.builder().pointer("/to/namespace").build()));
        }
        if (!dBaaService.isValidClassifierV3(updateClassifierRequest.getTo())) {
            errors.add(
                    new InvalidClassifierException("Classifier 'to' has no valid form. It must contains tenant or service 'scope', 'namespace' and 'microserviceName' but you have the following classifier ",
                            updateClassifierRequest.getTo(),
                            Source.builder().pointer("/to").build()));
        }
        if (!errors.isEmpty()) {
            throw new MultiValidationException(errors);
        }
        DatabaseRegistry fromDbRegistry;
        if (updateClassifierRequest.isFromV1orV2ToV3()) {
            fromDbRegistry = dBaaService.findDatabaseByOldClassifierAndType(updateClassifierRequest.getFrom(), type, false);
            if (fromDbRegistry == null) {
                throw new DbNotFoundException(type, updateClassifierRequest.getFrom(), Source.builder().pointer("/from").build());
            }
        } else {
            fromDbRegistry = dBaaService.findDatabaseByClassifierAndType(updateClassifierRequest.getFrom(), type, false);
            if (fromDbRegistry == null) {
                throw new DbNotFoundException(type, updateClassifierRequest.getFrom(), Source.builder().pointer("/from").build());
            }
        }
        DatabaseRegistry foundDb = dBaaService.findDatabaseByClassifierAndType(updateClassifierRequest.getTo(), type, false);
        if (foundDb != null) {
            throw new DBCreationConflictException(
                    String.format("Logical database with 'to' classifier %s and type %s already exists in namespace %s",
                            updateClassifierRequest.getTo(), type, updateClassifierRequest.getTo().get(NAMESPACE)));
        }

        DatabaseRegistry updatedDatabase;
        if (updateClassifierRequest.isFromV1orV2ToV3()) {
            updatedDatabase = dBaaService.updateFromOldClassifierToClassifier(updateClassifierRequest.getFrom(),
                    updateClassifierRequest.getTo(), type, updateClassifierRequest.isClone());
        } else {
            updatedDatabase = dBaaService.updateClassifier(updateClassifierRequest.getFrom(), updateClassifierRequest.getTo(),
                    type, updateClassifierRequest.isClone());
        }
        DatabaseResponseV3 response = new DatabaseResponseV3ListCP(updatedDatabase.getDatabaseRegistry().stream()
                .filter(dbr -> dbr.getClassifier().equals(updateClassifierRequest.getTo())).findFirst().orElseThrow(),
                physicalDatabaseDbaasRepository.findByAdapterId(updatedDatabase.getAdapterId()).getPhysicalDatabaseIdentifier());
        log.info("Database classifier was successfully updated. Database: {}", response);
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Update existing database connection properties",
            description = "The API allows to update existing database connection properties")
    @APIResponses({
            @APIResponse(responseCode = "400", description = "Database classifier or new connection properties must not be nil", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "400", description = "New connection properties must contain key 'role'", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "400", description = "Connection properties containing encryptedPassword must be stored in encrypted form, not as plaintext", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "400", description = "The classifier contains a namespace that differs from the one in the path", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "there is no existing database with such type and classifier", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Database with classifier does not contain connection properties for role", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "200", description = "Database connection properties were updated successfully", content = @Content(schema = @Schema(implementation = Database.class)))
    })
    @Path("/databases/update-connection/{type}")
    @PUT
    @RolesAllowed(DB_EDITOR)
    public Response updateConnectionProperties(@Parameter(description = "Project namespace in which the databases are used", required = true)
                                               @PathParam(NAMESPACE_PARAMETER) String namespace,
                                               @Parameter(description = "Type of physical database where database was created, e.g. mongodb, postgresql", required = true)
                                               @PathParam("type") String type,
                                               @Parameter(description = "Contains classifier and new connection properties", required = true)
                                               UpdateConnectionPropertiesRequest updateConnectionPropertiesRequest) {

        log.info("Get request on update connection properties for database with type={} and classifier={} and namespace={}",
                type, updateConnectionPropertiesRequest.getClassifier(), namespace);
        List<ValidationException> errors = new ArrayList<>();
        if (isUpdateConnectionRequestBodyValid(updateConnectionPropertiesRequest)) {
            log.error("Database classifier or new connection properties must not be empty: {}", updateConnectionPropertiesRequest);
            errors.add(new InvalidUpdateConnectionPropertiesRequestException("Database classifier or new connection properties must not be empty",
                    Source.builder().build()));
        }
        if (!updateConnectionPropertiesRequest.getConnectionProperties().containsKey(ROLE)) {
            log.error("New connection properties must contain key 'role': {}", updateConnectionPropertiesRequest);
            errors.add(new InvalidUpdateConnectionPropertiesRequestException("New connection properties must contain key 'role'",
                    Source.builder().build()));
        }
        if (updateConnectionPropertiesRequest.getConnectionProperties().containsKey("encryptedPassword")) {
            try {
                encryptionServiceProvider.getEncryptionService((String) updateConnectionPropertiesRequest.getConnectionProperties().get("encryptedPassword"));
            } catch (NoSuchElementException e) {
                log.error("Connection properties containing encryptedPassword must be stored in encrypted form, not as plaintext");
                errors.add(new InvalidUpdateConnectionPropertiesRequestException("Connection properties containing encryptedPassword must be stored in encrypted form, not as plaintext",
                        Source.builder().build()));
            }
        }
        if (!errors.isEmpty()) {
            throw new MultiValidationException(errors);
        }
        DatabaseRegistry foundDb = dBaaService.findDatabaseByClassifierAndType(updateConnectionPropertiesRequest.getClassifier(), type, true);
        if (foundDb == null) {
            log.error("Database with classifier={} is not found.", updateConnectionPropertiesRequest.getClassifier());
            throw new DbNotFoundException(type, updateConnectionPropertiesRequest.getClassifier(), Source.builder().pointer("/classifier").build());
        }
        String roleFromRequest = (String) updateConnectionPropertiesRequest.getConnectionProperties().get(ROLE);
        if (!isDatabaseContainsConnectionPropertiesForRole(roleFromRequest, foundDb.getDatabase().getConnectionProperties())) {
            log.error("Database with classifier={} does not contain connection properties for role={}.", updateConnectionPropertiesRequest.getClassifier(), roleFromRequest);
            throw new NotExistingConnectionPropertiesException(roleFromRequest);
        }
        Optional<DatabaseRegistry> updatedDatabaseRegistry = Optional.ofNullable(dBaaService.updateDatabaseConnectionProperties(updateConnectionPropertiesRequest, type));
        DatabaseResponseV3 response = dBaaService.processConnectionPropertiesV3(updatedDatabaseRegistry.orElseThrow(), roleFromRequest);
        log.info("Database connection properties were successfully updated.");
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Link databases for the requested microservices to target namespace",
            description = "Create additional classifiers for required databases in the target namespace, if there is no such classifiers.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "All databases for requested microservices were linked to target namespace", content = @Content(schema = @Schema(implementation = DatabaseResponseV3ListCP.class, type = SchemaType.ARRAY))),
            @APIResponse(responseCode = "400", description = "Request does not pass validation. Maybe some required fields are empty", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "500", description = "Some error during databases linking", content = @Content(schema = @Schema(implementation = String.class)))
    })
    @Path("/databases/link")
    @POST
    @RolesAllowed(DB_EDITOR)
    @Transactional
    public Response linkDatabases(@Parameter(description = "Project namespace in which the databases are used", required = true)
                                   @PathParam(NAMESPACE_PARAMETER) String namespace,
                                  @Parameter(description = "Request body must contain list of microservice names to link " +
                                                            "and target namespace, to which databases for these microservices will be linked.", required = true)
                                  LinkDatabasesRequest linkDatabasesRequest) {
        log.info("Get request on link databases for microservices={} from namespace={} to namespace={}",
                linkDatabasesRequest.getServiceNames(), namespace, linkDatabasesRequest.getTargetNamespace());
        List<ValidationException> errors = new ArrayList<>();
        if (!isLinkDatabasesRequestBodyValid(linkDatabasesRequest)) {
            log.error("Service names and target namespace must not be empty: {}", linkDatabasesRequest);
            errors.add(new InvalidLinkDatabasesRequestException("Service names and target namespace must not be empty",
                    Source.builder().build()));
        }
        if (!errors.isEmpty()) {
            throw new MultiValidationException(errors);
        }
        List<DatabaseRegistry> linkedRegistries = operationService.linkDbsToNamespace(namespace, linkDatabasesRequest);
        List<DatabaseResponseV3ListCP> response = linkedRegistries.stream()
                .map(dbr -> dBaaService.processConnectionPropertiesV3(dbr))
                .toList();
        log.info("{} databases were successfully linked to {} namespace.", linkedRegistries.size(), linkDatabasesRequest.getTargetNamespace());
        return Response.ok(response).build();
    }

    private boolean isLinkDatabasesRequestBodyValid(LinkDatabasesRequest linkDatabasesRequest) {
        return CollectionUtils.isNotEmpty(linkDatabasesRequest.getServiceNames()) &&
               StringUtils.isNotEmpty(linkDatabasesRequest.getTargetNamespace());
    }

    private boolean isUpdateConnectionRequestBodyValid(UpdateConnectionPropertiesRequest updateConnectionPropertiesRequest) {
        return MapUtils.isEmpty(updateConnectionPropertiesRequest.getClassifier()) ||
                MapUtils.isEmpty(updateConnectionPropertiesRequest.getConnectionProperties());
    }

    private boolean isDatabaseContainsConnectionPropertiesForRole(String role, List<Map<String, Object>> databaseProperties) {
        return ConnectionPropertiesUtils.checkRoleExistence(role, databaseProperties);
    }

    private boolean checkClassifier(SortedMap<String, Object> classifier, String namespace) {
        if (classifier == null) {
            return false;
        }
        if (classifier.containsKey(NAMESPACE)) {
            return Objects.equals(classifier.get(NAMESPACE), namespace);
        }
        return false;
    }

    private List<ValidationException> validateRecreateDbRequest(String namespace, List<RecreateDatabaseRequest> recreateDatabasesRequests) {
        List<ValidationException> errors = new ArrayList<>();
        Map<String, PhysicalDatabase> dbsMap = new HashMap<>();
        // get and check that db exists with this classifier
        for (int i = 0; i < recreateDatabasesRequests.size(); i++) {
            RecreateDatabaseRequest recreateDbRequest = recreateDatabasesRequests.get(i);
            if (!dBaaService.isValidClassifierV3(recreateDbRequest.getClassifier())) {
                errors.add(new InvalidClassifierException("Is not valid classifier V3 = " + recreateDbRequest.getClassifier(), recreateDbRequest.getClassifier(),
                        Source.builder().pointer("/" + i + "/physicalDatabaseId").build()));
            }
            PhysicalDatabase physDb = dbsMap.computeIfAbsent(recreateDbRequest.getPhysicalDatabaseId(), physicalDatabaseDbaasRepository::findByPhysicalDatabaseIdentifier);
            if (physDb == null) {
                errors.add(new UnregisteredPhysicalDatabaseException(Source.builder().pointer("/" + i + "/physicalDatabaseId").build(),
                        "Identifier: " + recreateDbRequest.getPhysicalDatabaseId()));
            } else {
                Optional<DatabaseRegistry> databaseRegistry = getDatabase(namespace, recreateDbRequest);
                if (databaseRegistry.isEmpty()) {
                    errors.add(new DbNotFoundException(recreateDbRequest.getType(), recreateDbRequest.getClassifier(),
                            Source.builder().pointer("/" + i + "/classifier").build()));
                }
            }
        }
        return errors;
    }

    private Optional<DatabaseRegistry> getDatabase(String namespace, RecreateDatabaseRequest recreateDatabaseRequest) {
        Map<String, Object> classifier = recreateDatabaseRequest.getClassifier();
        classifier.put("namespace", namespace);
        Optional<DatabaseRegistry> databaseRegistry = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, recreateDatabaseRequest.getType());
        return databaseRegistry;
    }

}
