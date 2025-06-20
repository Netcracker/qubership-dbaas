package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.dto.adapter.AccessGrantsResponse;
import org.qubership.cloud.dbaas.dto.v3.ErrorMessage;
import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;
import org.qubership.cloud.dbaas.service.DatabaseRolesService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.DbaasApiPath.ACCESS_GRANTS_SUBPATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.DATABASE_OPERATION_PATH_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;

@Slf4j
@Tag(name = "Microservice controller v3",
        description = "This controller contains APIs based on microservice value.")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
@Path(DATABASE_OPERATION_PATH_V3)
public class MicroserviceBasedController {

    @Inject
    private DatabaseRolesService databaseRolesService;

    @Operation(summary = "V3. Get actual access grants for microservice databases",
            description = "The API allows to get actual access grants of microservice databases.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Access grants was successfully found", content = @Content(schema = @Schema(implementation = AccessGrantsResponse.class))),
            @APIResponse(responseCode = "404", description = "Access grants for service's databases was not founded", content = @Content(schema = @Schema(implementation = String.class))),
    })
    @GET
    @Path(ACCESS_GRANTS_SUBPATH_V3)
    public Response getAccessGrants(@Parameter(description = "Project namespace in which the databases are used")
                                    @PathParam(NAMESPACE_PARAMETER) String namespace,
                                    @Parameter(description = "Microservice name")
                                    @PathParam("serviceName") String serviceName) {
        log.info("Receive request to get actual access grants on service={} in namespace={}", serviceName, namespace);
        Optional<DatabaseRole> databaseRoleOpt = databaseRolesService.getAccessGrants(namespace, serviceName);
        if (databaseRoleOpt.isPresent()) {
            DatabaseRole databaseRole = databaseRoleOpt.get();
            AccessGrantsResponse accessGrantsResponse = new AccessGrantsResponse(databaseRole.getServices(),
                    databaseRole.getPolicies(),
                    databaseRole.getDisableGlobalPermissions());
            return Response.ok(accessGrantsResponse).build();
        }
        log.debug("Access grants for service's databases was not founded. Namespace = {}, serviceName = {}.", namespace, serviceName);
        return Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(
                String.format("Access grants for service's databases was not founded. Namespace = %s, serviceName = %s.",
                        namespace,
                        serviceName))).build();
    }
}
