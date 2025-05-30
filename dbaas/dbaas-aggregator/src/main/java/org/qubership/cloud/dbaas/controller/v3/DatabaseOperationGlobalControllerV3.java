package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.controller.abstact.AbstractDatabaseAdministrationController;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.UpdateHostRequest;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.ErrorCodes;
import org.qubership.cloud.dbaas.exceptions.RequestValidationException;
import org.qubership.cloud.dbaas.service.OperationService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
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

import java.util.List;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.DbaasApiPath.DATABASE_GLOBAL_OPERATION_PATH_V3;
import static org.qubership.cloud.dbaas.controller.error.Utils.createTmfErrorResponse;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

@Slf4j
@Path(DATABASE_GLOBAL_OPERATION_PATH_V3)
@Tag(name = "Global Database Operation Controller v3",
        description = "This controller provides APIs for performing operations on existing databases and users " +
                "without requiring a specific namespace in the endpoints.")
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseOperationGlobalControllerV3 extends AbstractDatabaseAdministrationController {

    private OperationService operationService;

    public DatabaseOperationGlobalControllerV3() {}

    @Inject
    public DatabaseOperationGlobalControllerV3(OperationService operationService) {
        this.operationService = operationService;
    }


    @Operation(summary = "V3. Update Physical Host in Connection Properties",
            description = "This API updates the physical database host in the connection properties of logical databases. " +
                    "In the request body, you can specify whether to create a copy of the registry record (default behavior) " +
                    "or to make changes in place. If changes are made in place, the original database record will be updated.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "The host have been updated successfully",
                    content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = DatabaseResponseV3ListCP.class)))
    })
    @Path("/update-host")
    @POST
    @RolesAllowed(DB_CLIENT)
    public Response updateHost(@Parameter(description = "List of request objects", required = true)
                                   List<UpdateHostRequest> updateHostRequests) {
        log.info("Received request to change physical db host of logical db {}", updateHostRequests);
        Response response = validateRequest(updateHostRequests);
        if (response != null) {
            log.error("request is invalid {}", response);
            return response;
        }
        List<DatabaseRegistry> databaseRegistries = operationService.changeHost(updateHostRequests);
        List<DatabaseResponseV3ListCP> dbResponse = responseHelper.toDatabaseResponse(databaseRegistries, false);
        return Response.ok(dbResponse).build();
    }

    private Response validateRequest(List<UpdateHostRequest> updateHostRequests) {
        for (int i = 0; i < updateHostRequests.size(); i++) {
            UpdateHostRequest updateHostRequest = updateHostRequests.get(i);
            if (MapUtils.isEmpty(updateHostRequest.getClassifier()) ||
                    StringUtils.isEmpty(updateHostRequest.getType())) {
                return badRequestTmfResponse(i, "classifier and type");
            }
            if (StringUtils.isEmpty(updateHostRequest.getPhysicalDatabaseHost()) ||
                    StringUtils.isEmpty(updateHostRequest.getPhysicalDatabaseId())) {
                return badRequestTmfResponse(i, "physicalDatabaseId and physicalDatabaseHost");
            }
        }
        return null;
    }

    private static Response badRequestTmfResponse(int pointer, String... args) {
        return createTmfErrorResponse(
                new RequestValidationException(ErrorCodes.CORE_DBAAS_4043,
                        ErrorCodes.CORE_DBAAS_4043.getDetail(args), Source.builder().pointer("/" + pointer).build()),
                BAD_REQUEST,
                null
        );
    }

}
