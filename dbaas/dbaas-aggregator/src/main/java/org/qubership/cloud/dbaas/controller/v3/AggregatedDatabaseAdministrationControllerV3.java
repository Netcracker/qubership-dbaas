package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.controller.abstact.AbstractDatabaseAdministrationController;
import org.qubership.cloud.dbaas.dto.ClassifierWithRolesRequest;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.BgDomain;
import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.NotFoundException;
import org.qubership.cloud.dbaas.exceptions.*;
import org.qubership.cloud.dbaas.monitoring.model.DatabasesInfo;
import org.qubership.cloud.dbaas.service.*;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.DbaasApiPath.*;
import static org.qubership.cloud.dbaas.service.AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationServiceConst.*;

@Slf4j
@Path(DATABASES_PATH_V3)
@Tag(name = "Controller Database administration", description = "Allows to create, access and drop databases. " +
        "This API uses classifier as a key to create and retrieve databases. " +
        "Classifier is an abstract key that could be any JSON object mapping to (String -> Object) map. " +
        "For example classifier could be " +
        "&#123;" +
        "   \"tenantId\": \"uuid\"," +
        "   \"namespace\": \"cloud-catalog-ci\"," +
        "   \"microserviceName\": \"product-catalog-manager\"" +
        "&#125;")
@Produces(MediaType.APPLICATION_JSON)
public class AggregatedDatabaseAdministrationControllerV3 extends AbstractDatabaseAdministrationController {

    @Inject
    AggregatedDatabaseAdministrationService aggregatedDatabaseAdministrationService;
    @Inject
    DatabaseRolesService databaseRolesService;
    @Inject
    DBaaService dBaaService;
    @Inject
    MonitoringService monitoringService;
    @Inject
    PasswordEncryption encryption;
    @Inject
    BlueGreenService blueGreenService;

    @Operation(summary = "V3. Creates new database V3",
            description = "Creates new database and returns it with connection information, " +
                    "or returns the already created database if it exists. This version differs from version v1 in that there is the additional require field \"dbOwner\", " +
                    "which should contain name of database owner. It can be for exammple microserviceName value.")
    @APIResponses({
            @APIResponse(responseCode = "403", description = ACCESS_NAMESPACE_FORBIDDEN, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "400", description = NO_ADAPTER_MSG, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "401", description = ROLE_IS_NOT_ALLOWED, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "201", description = "Database created", content = @Content(schema = @Schema(implementation = Database.class))),
            @APIResponse(responseCode = "200", description = "Already having such database", content = @Content(schema = @Schema(implementation = Database.class))),
            @APIResponse(responseCode = "202", description = "Database is in process of creation"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")})
    @PUT
    @RolesAllowed(DB_CLIENT)
    public Response createDatabase(@Parameter(description = "The model for creating the database in the DBaaS", required = true)
                                   DatabaseCreateRequestV3 createRequest,
                                   @Parameter(description = "Namespace where database will be placed", required = true)
                                   @PathParam(NAMESPACE_PARAMETER) String namespace,
                                   @Parameter(description = "Determines if database should be created asynchronously")
                                   @QueryParam(ASYNC_PARAMETER) Boolean async) {
        if (!AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isClassifierCorrect(createRequest.getClassifier())) {
            throw new InvalidClassifierException("Classifier doesn't contain all mandatory fields. " +
                    "Check that classifier has `microserviceName`, `scope`. If `scope` = `tenant`, classifier must contain `tenantId` property",
                    createRequest.getClassifier(), Source.builder().pointer("/classifier").build());
        }
        checkOriginService(createRequest);

        namespace = (String) createRequest.getClassifier().get(NAMESPACE);
        Response activeDbByControllerNamespace = getDbActiveNamespaceByControllerNamespaceForComposite(createRequest, namespace);
        if (activeDbByControllerNamespace != null) {
            return activeDbByControllerNamespace;
        }

        String supportedRole = getSupportedRole(createRequest, namespace);
        log.debug("requested role={} to create database", supportedRole);
        Response responseEntity = aggregatedDatabaseAdministrationService.createDatabaseFromRequest(createRequest,
                namespace,
                (database, role) -> dBaaService.providePasswordFor(database, role),
                supportedRole, null, async);
        log.info("New database was created {}", responseEntity);
        return responseEntity;
    }

    @Nullable
    private Response getDbActiveNamespaceByControllerNamespaceForComposite(DatabaseCreateRequestV3 createRequest, String cntrNamespace) {
        Optional<BgDomain> bgDomain = blueGreenService.getDomainByControllerNamespace(cntrNamespace);
        boolean isControllerNamespace = bgDomain.isPresent();
        DatabaseRegistry dbInControllerNamespace = dBaaService.findDatabaseByClassifierAndType(createRequest.getClassifier(), createRequest.getType(), false);
        if (isControllerNamespace && dbInControllerNamespace == null) {
            String activeNamespace = getActiveNamespaceFromDomain(bgDomain.get());
            DatabaseRegistry databaseInActiveNamespace = findDbInActiveNamespace(createRequest, activeNamespace);
            checksForDatabaseInActiveNamespace(databaseInActiveNamespace);
            String supportedRole = getSupportedRole(createRequest, cntrNamespace);
            return Response.ok(dBaaService.processConnectionPropertiesV3(databaseInActiveNamespace, supportedRole)).build();
        }
        return null;
    }

    @NotNull
    private String getActiveNamespaceFromDomain(BgDomain bgDomain) {
        Optional<BgNamespace> activeNamespace = bgDomain.getNamespaces().stream().filter(o -> ACTIVE_STATE.equals(o.getState())).findFirst();
        if (activeNamespace.isEmpty()) {
            throw new NotFoundException("Can't find active namespace in bg domain");
        }
        return activeNamespace.get().getNamespace();
    }

    @NotNull
    private String getSupportedRole(DatabaseCreateRequestV3 createRequest, String namespace) {
        String supportedRole = databaseRolesService.getSupportedRoleFromRequest(createRequest, createRequest.getType(),
                namespace);
        if (supportedRole == null) {
            throw new NotSupportedServiceRoleException(createRequest.getUserRole());
        }
        return supportedRole;
    }


    private DatabaseRegistry findDbInActiveNamespace(DatabaseCreateRequestV3 createRequest, String activeNamespace) {
        SortedMap<String, Object> activeNamespaceClassifier = changeClassifierToActiveNamespace(createRequest.getClassifier(), activeNamespace);
        return dBaaService.findDatabaseByClassifierAndType(activeNamespaceClassifier, createRequest.getType(), false);
    }


    private void checksForDatabaseInActiveNamespace(DatabaseRegistry databaseInActiveNamespace) {
        if (databaseInActiveNamespace == null) {
            throw new NoDatabaseInActiveNamespaceException("Can't find database in active namespace");
        }
        if (databaseInActiveNamespace.getBgVersion() != null) {
            throw new InteractionWithNotVersionedDbException("Can't interact with versioned database");
        }
    }

    private SortedMap<String, Object> changeClassifierToActiveNamespace(Map<String, Object> classifier, String activeNamespace) {
        SortedMap<String, Object> classifierResult = new TreeMap<>(classifier);
        classifierResult.put(NAMESPACE, activeNamespace);
        return classifierResult;
    }

    @Operation(summary = "V3. List of all databases",
            description = "Returns the list of all databases.")
    @APIResponses({
            @APIResponse(responseCode = "500", description = "Internal error"),
            @APIResponse(responseCode = "200", description = "List of databases in namespace", content = @Content(schema = @Schema(type = SchemaType.ARRAY)))})
    @Path(LIST_DATABASES_PATH)
    @GET
    @RolesAllowed({DB_CLIENT, DISCR_TOOL_CLIENT})
    public Response getAllDatabases(@Parameter(description = "Project namespace in which the databases is used", required = true)
                                    @PathParam(NAMESPACE_PARAMETER) String namespace,
                                    @Parameter(description = "Parameter for adding database resources to response", required = true)
                                    @QueryParam("withResources") @DefaultValue("false") Boolean withResources) {
        log.info("Get all databases in {}", namespace);
        List<DatabaseResponseV3ListCP> response = responseHelper.toDatabaseResponse(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(namespace), withResources);
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Get database by classifier",
            description = "Returns connection to an already created database using classifier to search")
    @APIResponses({
            @APIResponse(responseCode = "404", description = "Cannot find database with such classifier", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "401", description = ROLE_IS_NOT_ALLOWED, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "200", description = "Successfully found database", content = @Content(schema = @Schema(implementation = Database.class, type = SchemaType.ARRAY)))})
    @Path("/get-by-classifier/{type}")
    @POST
    @RolesAllowed({DB_CLIENT, DISCR_TOOL_CLIENT})
    @Transactional
    public Response getDatabaseByClassifier(@Parameter(description = "Classifier on which to search databases. Represents a \"key\":\"value\" view", required = true)
                                            ClassifierWithRolesRequest classifierRequest,
                                            @PathParam(NAMESPACE_PARAMETER) String namespace,
                                            @Parameter(description = "The type of base in which the database was created. For example PostgreSQL  or MongoDB", required = true)
                                            @PathParam("type") String type) {
        checkOriginService(classifierRequest);
        if (!dBaaService.isValidClassifierV3(classifierRequest.getClassifier())) {
            throw new InvalidClassifierException("Invalid V3 classifier", classifierRequest.getClassifier(), Source.builder().pointer("").build());
        }
        checkOriginService(classifierRequest);
        namespace = (String) classifierRequest.getClassifier().get(NAMESPACE);

        DatabaseCreateRequestV3 fakeCreateRequest = new DatabaseCreateRequestV3(classifierRequest.getClassifier(), type);
        fakeCreateRequest.setOriginService(classifierRequest.getOriginService());
        fakeCreateRequest.setUserRole(classifierRequest.getUserRole());
        Response activeDbByControllerNamespace = getDbActiveNamespaceByControllerNamespaceForComposite(fakeCreateRequest, namespace);
        if (activeDbByControllerNamespace != null) {
            return activeDbByControllerNamespace;
        }

        String supportedRole = databaseRolesService.getSupportedRoleFromRequest(classifierRequest, type, namespace);
        if (supportedRole == null) {
            throw new NotSupportedServiceRoleException(classifierRequest.getUserRole());
        }

        log.debug("Get by classifier {}", classifierRequest.getClassifier());
        DatabaseRegistry databaseRegistry = dBaaService.findDatabaseByClassifierAndType(classifierRequest.getClassifier(), type, false);
        log.info("Get by classifier {} database {}", classifierRequest.getClassifier(), databaseRegistry);
        if (databaseRegistry == null) {
            throw new DbNotFoundException(type, classifierRequest.getClassifier(), Source.builder().build());
        } else {
            return Response.ok(dBaaService.processConnectionPropertiesV3(databaseRegistry, supportedRole)).build();
        }
    }

    @Operation(summary = "V3. Clean namespace databases",
            description = "Drops all databases for the specific namespace",
            hidden = true)
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Databases with the specified namespace were drop successfully", content = @Content(schema = @Schema(implementation = String.class)))})
    @DELETE
    @RolesAllowed(NAMESPACE_CLEANER)
    @Transactional
    public Response dropAllDatabasesInNamespaceAPI(@Parameter(description = "Namespace to clean, operation would drop all databases in that cloud project", required = true)
                                                   @PathParam(NAMESPACE_PARAMETER) String namespace,
                                                   @Parameter(description = "Boolean value to remove or not to remove rules of namespace. ", required = false)
                                                   @QueryParam("deleteRules") Boolean deleteRules) {
        return this.dropAllDatabasesInNamespace(namespace, deleteRules);
    }

    @Operation(summary = "V3. Deprecated. Get list of ghosts and lost databases",
            description = "Databases may get lost if they were marked to delete but were not actually deleted. " +
                    "An existing database stays as a ghost if it was not registered in DBaaS.")
    @APIResponses({
            @APIResponse(responseCode = "500", description = "Internal error"),
            @APIResponse(responseCode = "200",
                    description = "List of ghosts and lost databases",
                    content = @Content(schema = @Schema(implementation = DatabasesInfo.class)))})
    @Path("/statuses")
    @GET
    @Deprecated
    @RolesAllowed(DB_CLIENT)
    public Response getDatabasesStatus(@Parameter(description = "Namespace for which to get the database statuses", required = true)
                                           @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Get databases statuses");
        return Response.ok(monitoringService.getDatabasesStatus()).build();
    }

    @Operation(summary = "V3. External database registration",
            description = "This API supports registration in DbaaS for any external logical database.")
    @APIResponses({
            @APIResponse(responseCode = "500", description = "Internal error"),
            @APIResponse(responseCode = "200",
                    description = "Successfully found database",
                    content = @Content(schema = @Schema(implementation = Database.class))),
            @APIResponse(responseCode = "201",
                    description = "The database was added or updated successfully",
                    content = @Content(schema = @Schema(implementation = Database.class))),
            @APIResponse(responseCode = "401", description = ROLE_IS_NOT_ALLOWED, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "409", description = "Logical database with such classifier and type already exist in namespace and it is internal logical database", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "403", description = "Namespace in classifier and path variable are not equal", content = @Content(schema = @Schema(implementation = String.class)))
    })
    @Path("/registration/externally_manageable")
    @PUT
    @RolesAllowed(DB_CLIENT)
    public Response addExternalDatabase(@Parameter(description = "Request with connection information for new database", required = true)
                                        ExternalDatabaseRequestV3 externalDatabaseRequest,
                                        @Parameter(description = "Namespace with which new database will be connected", required = true)
                                        @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Get request on adding external database with classifier {} and type {} in namespace {}", externalDatabaseRequest.getClassifier(), externalDatabaseRequest.getType(), namespace);
        if (!AggregatedDatabaseAdministrationService.AggregatedDatabaseAdministrationUtils.isClassifierCorrect(externalDatabaseRequest.getClassifier())) {
            throw new InvalidClassifierException("Classifier doesn't contain all mandatory fields. " +
                    "Check that classifier has `microserviceName`, `scope`. If `scope` = `tenant`, classifier must contain `tenantId` property",
                    externalDatabaseRequest.getClassifier(), Source.builder().pointer("/classifier").build());
        }
        DatabaseRegistry databaseRegistry = externalDatabaseRequest.toDatabaseRegistry();
        Optional<BgDomain> bgDomainOpt = aggregatedDatabaseAdministrationService.getBgDomain(namespace);
        if (bgDomainOpt.isPresent()) {
            aggregatedDatabaseAdministrationService.saveExtraClassifierForDatabaseBaseOnBgDomain(bgDomainOpt.get(), databaseRegistry.getType(), namespace, databaseRegistry.getClassifier());
        }
        Optional<DatabaseRegistry> existingDatabaseOptional = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(databaseRegistry.getClassifier(),
                databaseRegistry.getType());
        boolean updateConnectionProperties = externalDatabaseRequest.getUpdateConnectionProperties() != null && externalDatabaseRequest.getUpdateConnectionProperties();
        if (existingDatabaseOptional.isPresent()) {
            DatabaseRegistry existingDatabaseRegistry = existingDatabaseOptional.get();
            if (!existingDatabaseRegistry.isExternallyManageable()) {
                throw new DBCreationConflictException(
                        String.format("Logical database with classifier %s and type %s already exists in namespace %s" +
                                        " but is not externally managed",
                                existingDatabaseRegistry.getClassifier(), existingDatabaseRegistry.getType(), existingDatabaseRegistry.getNamespace()));
            }
            log.info("Skip creation. External logical database with classifier {} and type {} already exists", databaseRegistry.getClassifier(), databaseRegistry.getType());
            if (!updateConnectionProperties) {
                final DatabaseResponseV3ListCP processedDb = dBaaService.processConnectionPropertiesV3(existingDatabaseRegistry);
                return Response.ok(new ExternalDatabaseResponseV3(processedDb)).build();
            } else {
                if (externalDatabaseRequest.getConnectionProperties().isEmpty()) {
                    throw new EmptyConnectionPropertiesException();
                }
                existingDatabaseRegistry.setConnectionProperties(databaseRegistry.getConnectionProperties());
            }
            databaseRegistry = existingDatabaseRegistry;
        }
        DatabaseRegistry createdDb = dBaaService.saveExternalDatabase(databaseRegistry);
        final DatabaseResponseV3ListCP processedDb = dBaaService.processConnectionPropertiesV3(createdDb);
        return Response.status(Response.Status.CREATED).entity(new ExternalDatabaseResponseV3(processedDb)).build();
    }

    @Operation(summary = "V3. Clean namespace databases",
            description = "Drops all databases for the specific namespace", hidden = true)
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Databases with the specified namespace were drop successfully", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "403", description = "Dbaas is working in PROD mode. Deleting logical databases is prohibited", content = @Content(schema = @Schema(implementation = String.class)))
    })
    @Override
    @Path("/deleteall")
    @DELETE
    @RolesAllowed(NAMESPACE_CLEANER)
    @Transactional
    public Response dropAllDatabasesInNamespace(@Parameter(description = "Namespace to clean, operation would drop all databases in that cloud project", required = true)
                                                @PathParam(NAMESPACE_PARAMETER) String namespace,
                                                @Parameter(description = "Boolean value to remove or not to remove rules of namespace. ", required = false)
                                                @QueryParam("deleteRules") Boolean deleteRules) {
        log.info("Received request to drop all databases in {} namespace with deleteRules {}", namespace, deleteRules);
        Optional<BgDomain> domain = blueGreenService.getBgDomainContains(namespace);
        if (domain.isPresent()) {
            Optional<BgNamespace> idleNamespace = domain.get().getNamespaces().stream().filter(o -> IDLE_STATE.equals(o.getState())).findFirst();
            idleNamespace.ifPresent(bgNamespace -> {
                        log.info("Dropping BG namespace with idle status: {}", bgNamespace.getNamespace());
                        super.dropAllDatabasesInNamespace(bgNamespace.getNamespace(), deleteRules);
                    }
            );
        }
        return super.dropAllDatabasesInNamespace(namespace, deleteRules);
    }


    @Operation(summary = " V3.Delete database by classifier",
            description = "Deletes database by id in the specific namespace.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Successfully deleted database.", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "401", description = ROLE_IS_NOT_ALLOWED, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Cannot find database with such classifier", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "403", description = ACCESS_NAMESPACE_FORBIDDEN, content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "406", description = "Dbaas is working in PROD mode. Deleting logical databases is prohibited", content = @Content(schema = @Schema(implementation = String.class)))})
    @Path("/{type}")
    @DELETE
    @RolesAllowed(DB_CLIENT)
    @Transactional
    public Response deleteDatabaseByClassifier(@Parameter(description = "A unique identifier of the document in the database", required = true)
                                               ClassifierWithRolesRequest classifierRequest,
                                               @Parameter(description = "Project namespace in which the base is used")
                                               @PathParam(NAMESPACE_PARAMETER) String namespace,
                                               @Parameter(description = "The physical type of logical database. For example mongodb or postgresql", required = true)
                                               @PathParam("type") String type) {
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        checkOriginService(classifierRequest);
        String supportedRole = databaseRolesService.getSupportedRoleFromRequest(classifierRequest, type, namespace);
        if (supportedRole == null || !supportedRole.equals(Role.ADMIN.toString())) {
            throw new NotSupportedServiceRoleException();
        }
        log.info("Drop database with classifier={} in namespace={}", classifierRequest.getClassifier(), namespace);
        Optional<DatabaseRegistry> databaseRegistryOptional = databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifierRequest.getClassifier(), type);
        if (databaseRegistryOptional.isPresent()) {
            DatabaseRegistry databaseRegistry = databaseRegistryOptional.get();
            if (!databaseRegistry.getNamespace().equals(namespace)) {
                throw new ForbiddenNamespaceException(namespace, databaseRegistry.getNamespace(),
                        Source.builder().pathVariable("namespace").build());
            }
            if (databaseRegistry.isExternallyManageable()) {
                dBaaService.dropExternalDatabase(databaseRegistry);
            } else {
                dBaaService.dropDatabase(databaseRegistry);
                encryption.deletePassword(databaseRegistry.getDatabase());
                databaseRegistryDbaasRepository.deleteById(databaseRegistry.getId());
            }
            log.info("Database in namespace={} with classifier={} is dropped", namespace, databaseRegistry.getClassifier());

        }
        //should return ok if db not found
        return Response.ok().build();
    }

    private void checkOriginService(UserRolesServices rolesServices) {
        if (rolesServices.getOriginService() == null || rolesServices.getOriginService().isEmpty()) {
            log.error("Request body={} must contain originService", rolesServices);
            throw new InvalidOriginServiceException();
        }
    }
}
