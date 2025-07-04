package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserRequest;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserResponse;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersRequest;
import org.qubership.cloud.dbaas.dto.v3.UserOperationRequest;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.userrestore.SuccessfulRestoreUsersResponse;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersResponse;
import org.qubership.cloud.dbaas.exceptions.DbNotFoundException;
import org.qubership.cloud.dbaas.exceptions.UserDeletionException;
import org.qubership.cloud.dbaas.exceptions.UserNotFoundException;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.UserService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.ObjectUtils;
import org.flywaydb.core.internal.util.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.DbaasApiPath.USERS_PATH_V3;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CREATED;

@Slf4j
@Path(USERS_PATH_V3)
@Tag(name = "Database users controller v3",
        description = "This controller contains API for operations with database users.")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class DatabaseUsersControllerV3 {

    @Inject
    private DBaaService dBaaService;

    @Inject
    private UserService userService;

    @Operation(summary = "V3. Get or create user",
            description = "The API allows to get or create specific user for database.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "User is already created", content = @Content(schema = @Schema(implementation = GetOrCreateUserResponse.class))),
            @APIResponse(responseCode = "201", description = "New user is created", content = @Content(schema = @Schema(implementation = GetOrCreateUserResponse.class))),
            @APIResponse(responseCode = "202", description = "Operation in progress", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Error", content = @Content(schema = @Schema(implementation = String.class))),
    })
    @PUT
    @Transactional
    public Response getOrCreateUser(@Parameter(description = "Contains classifier and information about user", required = true)
                                            GetOrCreateUserRequest getOrCreateUserRequest) {
        log.info("Get request to get or create database user. Request body {}", getOrCreateUserRequest);
        DatabaseRegistry foundDb = dBaaService.findDatabaseByClassifierAndType(getOrCreateUserRequest.getClassifier(), getOrCreateUserRequest.getType(), true);
        if (foundDb == null) {
            log.error("Database with classifier={} is not found.", getOrCreateUserRequest.getClassifier());
            throw new DbNotFoundException(getOrCreateUserRequest.getType(),
                    getOrCreateUserRequest.getClassifier(),
                    Source.builder().pointer("").build());
        }

        Optional<DatabaseUser> existingUserOpt = userService
                .findUserByLogicalUserIdAndDatabaseId(getOrCreateUserRequest.getLogicalUserId(), foundDb.getDatabase());
        if (existingUserOpt.isPresent()) {
            if (existingUserOpt.get().getStatus().equals(DatabaseUser.Status.CREATING)) {
                return Response.accepted(
                        new GetOrCreateUserResponse.GetOrCreateUserAcceptedResponse(
                                String.format("user with logicalUserId = %s, classifier = %s and type = %s is creating", getOrCreateUserRequest.getLogicalUserId(), getOrCreateUserRequest.getClassifier(), getOrCreateUserRequest.getType()),
                                getOrCreateUserRequest)).build();
            }
            DatabaseUser existingUser = existingUserOpt.get();
            log.info("Requested user with logicalUserId = {} is already existed", getOrCreateUserRequest.getLogicalUserId());
            existingUser.getConnectionProperties().put("logicalUserId", existingUser.getLogicalUserId());
            userService.decryptPassword(existingUser);
            dBaaService.getConnectionPropertiesService().addAdditionalPropToCP(existingUser);
            return Response.ok(new GetOrCreateUserResponse(existingUser.getUserId().toString(),
                    existingUser.getConnectionProperties())).build();
        }
        try {
            GetOrCreateUserResponse response = userService.createUser(getOrCreateUserRequest, foundDb.getDatabase());
            return Response.status(CREATED).entity(response).build();
        } catch (Exception e) {
            Optional<DatabaseUser> userOptional = userService
                    .findUserByLogicalUserIdAndDatabaseId(getOrCreateUserRequest.getLogicalUserId(), foundDb.getDatabase());
            if (userOptional.isPresent() && userOptional.get().getStatus().equals(DatabaseUser.Status.CREATING)) {
                userService.removeFromDbaasStorage(userOptional.get());
            }
            throw e;
        }
    }

    @Operation(summary = "V3. Delete user",
            description = "The API allows to delete specific user for database.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "User is successfully deleted", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "204", description = "User is not found", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Error", content = @Content(schema = @Schema(implementation = String.class))),
    })
    @DELETE
    @Transactional
    public Response deleteUser(@Parameter(description = "Contains userId or classifier, logicalUserId and type field for user identification.", required = true)
                                       UserOperationRequest deleteUserRequest) {
        log.info("Get request to delete database user. Request body {}", deleteUserRequest);
        if (!isUserOperationRequestValid(deleteUserRequest)) {
            log.error("Request body is not valid." +
                    "Delete user request must contains 'userId' field or 'classifier', 'logicalUserId' and 'type' fields.");
            return Response.status(BAD_REQUEST).entity("Request body is not valid." +
                    "Delete user request must contains 'userId' field or 'classifier', 'logicalUserId' and 'type' fields.").build();
        }
        Optional<DatabaseUser> userOpt = userService.findUser(deleteUserRequest);
        if (userOpt.isEmpty()) {
            return Response.noContent().build();
        }
        DatabaseUser user = userOpt.get();
        boolean isUserDeleted = userService.deleteUser(user);
        if (!isUserDeleted)
            throw new UserDeletionException(Response.Status.NOT_FOUND.getStatusCode(), deleteUserRequest.toString());
        return Response.ok("User successfully deleted").build();
    }

    @Operation(summary = "V3. Rotate user password.",
            description = "The API allows to rotate password for specific user.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Password was successfully reset.", content = @Content(schema = @Schema(implementation = String.class))),
            @APIResponse(responseCode = "404", description = "Error", content = @Content(schema = @Schema(implementation = String.class))),
    })
    @Path("/rotate-password")
    @POST
    @Transactional
    public Response rotateUserPassword(@Parameter(description = "Contains userId or classifier, logicalUserId and type field for user identification.", required = true)
                                               UserOperationRequest rotateUserPasswordRequest) {
        log.info("Get request to rotate password for database user. Request body {}", rotateUserPasswordRequest);
        if (!isUserOperationRequestValid(rotateUserPasswordRequest)) {
            log.error("Request body is not valid." +
                    "Rotate password user request must contains 'userId' field or 'classifier', 'logicalUserId' and 'type' fields.");
            return Response.status(BAD_REQUEST).entity("Request body is not valid." +
                    "Rotate password request must contains 'userId' field or 'classifier', 'logicalUserId' and 'type' fields.").build();
        }
        Optional<DatabaseUser> userOpt = userService.findUser(rotateUserPasswordRequest);
        if (userOpt.isEmpty()) {
            throw new UserNotFoundException(Response.Status.NOT_FOUND.getStatusCode(), Source.builder().build(), rotateUserPasswordRequest.toString());
        }
        DatabaseUser user = userOpt.get();
        user = userService.rotatePassword(user);
        return Response.ok(Map.of("connectionProperties", user.getConnectionProperties())).build();
    }

    @Operation(summary = "V3. Restore users",
            description = "The API allows to restore users for database. If role field is passed, then only users with this role will be processed.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Users recreated", content = @Content(schema = @Schema(implementation = SuccessfulRestoreUsersResponse.class))),
            @APIResponse(responseCode = "500", description = "Error during restore", content = @Content(schema = @Schema(implementation = RestoreUsersResponse[].class)))
    })
    @Path("/restore")
    @POST
    @Transactional
    public Response restoreUser(@Parameter(description = "Contains classifier, user role and physical database type", required = true)
                                             RestoreUsersRequest restoreUsersRequest) {
        log.info("Get request to restore users");
        RestoreUsersResponse restoreUsersResponse = userService.restoreUsers(restoreUsersRequest);
        if (!restoreUsersResponse.getUnsuccessfully().isEmpty()) {
            return Response.serverError().entity(restoreUsersResponse).build();
        }
        return Response.ok().entity(new SuccessfulRestoreUsersResponse("all users were restored")).build();
    }

    private boolean isUserOperationRequestValid(@NotNull UserOperationRequest request) {
        if (StringUtils.hasLength(request.getUserId())) {
            return true;
        }
        return StringUtils.hasLength(request.getUserId())
                || !ObjectUtils.isEmpty(request.getClassifier())
                || StringUtils.hasLength(request.getType());
    }
}

