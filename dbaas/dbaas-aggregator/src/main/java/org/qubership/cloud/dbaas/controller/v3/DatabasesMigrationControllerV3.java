package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.API_VERSION;
import org.qubership.cloud.dbaas.dto.RegisterDatabaseWithUserCreationRequest;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.migration.RegisterDatabaseResponseBuilder;
import org.qubership.cloud.dbaas.dto.v3.RegisterDatabaseRequestV3;
import org.qubership.cloud.dbaas.exceptions.ErrorCodes;
import org.qubership.cloud.dbaas.exceptions.InvalidClassifierException;
import org.qubership.cloud.dbaas.exceptions.RequestValidationException;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.MigrationService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.flywaydb.core.internal.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.Constants.MIGRATION_CLIENT;
import static org.qubership.cloud.dbaas.Constants.NAMESPACE;

@Slf4j
@Path(DbaasApiPath.DATABASES_MIGRATION_PATH_V3)
@Tag(name = "Migration controller", description = "Provides API to migrate: database registration from another source, database passwords to external system.")
@Produces(MediaType.APPLICATION_JSON)
public class DatabasesMigrationControllerV3 {

    private static final Pattern DB_HOST_NAME_PATTERN = Pattern.compile("(^([A-z0-9-])+)\\.([A-z0-9-]+)$");

    @Inject
    MigrationService migrationService;
    @Inject
    DBaaService dBaaService;

    @Operation(summary = "V3. Register databases",
            description = "This API allows you to register the database in DBaaS. The registered database would not be created, and it is assumed that it already exists in the cluster " +
                    "The purpose for this API is to register databases which weren not created through DBaaS. If registered database already exist in DBaaS then it will not be added.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Migration completed", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "500", description = "Internal error during migration", content = @Content(schema = @Schema(implementation = String.class)))})
    @PUT
    @RolesAllowed(MIGRATION_CLIENT)
    @Transactional
    public Response registerDatabases(List<RegisterDatabaseRequestV3> databasesToRegister) {
        log.debug("Start validate classifiers");
        for (RegisterDatabaseRequestV3 request : databasesToRegister) {
            if (!dBaaService.isValidClassifierV3(request.getClassifier())) {
                log.error("RegisterDatabaseRequest={} contains not valid V3 classifier", request);
                throw new InvalidClassifierException("Invalid V3 classifier", request.getClassifier(), Source.builder().pointer("").build());
            }
        }
        return migrationService.registerDatabases(databasesToRegister, API_VERSION.V3, false)
                .buildAndResponse();
    }

    @Operation(summary = "V3. Register databases with user creation",
            description = "This API allows you to register the database in DBaaS with user creation. It can be useful when username and password is unknown " +
                    "and you want you database to dbaas registry. Previous users will not be unbind, so in point of security you should unbind user them by yourself." +
                    "The registered database would not be created, and it is assumed that it already exists in the cluster " +
                    "The purpose for this API is to register databases which were not created through DBaaS. If registered database already exist in DBaaS then it will not be added.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Migration completed. Response is a map where key is database type and value is MigrationResult", content = @Content(schema = @Schema(implementation = RegisterDatabaseResponseBuilder.MigrationResult.class))),
            @APIResponse(responseCode = "409", description = "There are some conflicts detected during migration. Response is a map where key is database type and value is MigrationResult", content = @Content(schema = @Schema(implementation = RegisterDatabaseResponseBuilder.MigrationResult.class))),
            @APIResponse(responseCode = "500", description = "Internal error during migration. Response is a map where key is database type and value is MigrationResult", content = @Content(schema = @Schema(implementation = RegisterDatabaseResponseBuilder.MigrationResult.class)))})
    @Path("/with-user-creation")
    @PUT
    @RolesAllowed({MIGRATION_CLIENT, DB_CLIENT})
    public Response registerDatabasesWithUserCreation(List<RegisterDatabaseWithUserCreationRequest> databasesToRegister) {
        log.info("get request to register logical databases");
        validateRequest(databasesToRegister);
        for (RegisterDatabaseWithUserCreationRequest request : databasesToRegister) {
            if (!dBaaService.isValidClassifierV3(request.getClassifier())) {
                log.error("request body contains not valid V3 classifier: {}", request);
                throw new InvalidClassifierException("It does not match v3 format", request.getClassifier(), Source.builder().pointer("").build());
            }
        }
        List<RegisterDatabaseRequestV3> registerDatabaseRequest = databasesToRegister.stream()
                .map(RegisterDatabaseRequestV3::new).collect(Collectors.toList());
        return migrationService.registerDatabases(registerDatabaseRequest, API_VERSION.V3, true)
                .buildAndResponse();
    }

    private void validateRequest(List<RegisterDatabaseWithUserCreationRequest> databasesToRegister) {
        for (int i = 0; i < databasesToRegister.size(); i++) {
            RegisterDatabaseWithUserCreationRequest request = databasesToRegister.get(i);
            if (request.getType() == null || request.getType().isEmpty()) {
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4027,
                        ErrorCodes.CORE_DBAAS_4027.getDetail("dbType"), Source.builder().pointer("/" + i + "/type").build());
            }
            if (request.getName() == null || request.getName().isEmpty()) {
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4027,
                        ErrorCodes.CORE_DBAAS_4027.getDetail("database name"), Source.builder().pointer("/" + i + "/name").build());
            }
            if (!StringUtils.hasText(request.getPhysicalDatabaseId()) && !StringUtils.hasText(request.getDbHost())) {
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4027,
                        ErrorCodes.CORE_DBAAS_4027.getDetail("physical database id or dbHost"), Source.builder().pointer("/" + i + "/physicalDatabaseId").build());
            }
            if (StringUtils.hasText(request.getDbHost()) && !isDbHostHasValidFormat(request.getDbHost())) {
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4028,
                        ErrorCodes.CORE_DBAAS_4028.getDetail(request.getDbHost()), Source.builder().pointer("/" + i + "/dbHost").build());

            }
            if (request.getClassifier() == null || request.getClassifier().isEmpty()) {
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4027,
                        ErrorCodes.CORE_DBAAS_4027.getDetail("classifier"), Source.builder().pointer("/" + i + "/classifier").build());
            }
            if (request.getClassifier().get(NAMESPACE) == null || request.getClassifier().get(NAMESPACE).toString().isEmpty()) {
                throw new RequestValidationException(ErrorCodes.CORE_DBAAS_4027,
                        ErrorCodes.CORE_DBAAS_4027.getDetail("namespace in classifier"), Source.builder().pointer("/" + i + "/classifier/namespace").build());
            }
        }
    }

    private boolean isDbHostHasValidFormat(String dbHost) {
        Matcher matcher = DB_HOST_NAME_PATTERN.matcher(dbHost);
        return matcher.matches();
    }
}
