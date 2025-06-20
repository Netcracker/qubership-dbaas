package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.v3.RestorePasswordRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;

@Slf4j
@Path(DbaasApiPath.INTERNAL_PHYSICAL_DATABASES_PATH)
@Tag(name = "Controller for internal operations")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class InternalOperationController {

    @Inject
    private PhysicalDatabasesService physicalDatabasesService;

    @Inject
    private DBaaService dBaaService;

    @Operation(summary = "Passwords restoration",
            description = "Sends requests to adapter to start restoration process", hidden = true)
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Successfully started restoration process.")})
    @Path("/users/restore-password")
    @POST
    @Transactional
    public Response restoreUsers(@Parameter(description = "Parameters for password restoration process.", required = true)
                                 RestorePasswordRequest request) {
        log.info("Got request to restore passwords in physical db {} of {} type", request.getPhysicalDbId(), request.getType());
        DbaasAdapter adapter = physicalDatabasesService.getAdapterByPhysDbId(request.getPhysicalDbId());
        List<Database> databases = physicalDatabasesService.getDatabasesByPhysDbAndType(request.getPhysicalDbId(), request.getType());
        List<Map<String, Object>> connectionProperties = databases.stream()
                .peek(db -> dBaaService.decryptPassword(db))
                .map(Database::getConnectionProperties)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        log.debug("Found {} users which require password restoration", connectionProperties.size());
        Response.StatusType responseStatus = adapter.restorePasswords(request.getSettings(), connectionProperties);
        return Response.status(responseStatus).build();
    }
}
