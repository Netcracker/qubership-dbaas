package com.netcracker.cloud.dbaas.controller;

import com.netcracker.cloud.dbaas.DbaasApiPath;
import com.netcracker.cloud.dbaas.dto.ApiVersionInfo;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Slf4j
@Path(DbaasApiPath.API_VERSION)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "API version controller", description = "Providing API version info")
@PermitAll
public class ApiVersionController {

    private static final int API_MINOR = 29;
    private static final int API_BLUE_GREEN_MINOR = 4;
    private static final int API_DECLARATIVE_MINOR = 0;
    private static final int API_COMPOSITE_MINOR = 0;
    private static final int API_BACKUPS_MINOR = 0;

    private final ApiVersionInfo API_VERSION_INFO =
            new ApiVersionInfo(List.of(
                    new ApiVersionInfo.ApiVersionElement("/api", 3, API_MINOR, List.of(3)),
                    new ApiVersionInfo.ApiVersionElement("/api/bluegreen", 1, API_BLUE_GREEN_MINOR, List.of(1)),
                    new ApiVersionInfo.ApiVersionElement("/api/declarations", 1, API_DECLARATIVE_MINOR, List.of(1)),
                    new ApiVersionInfo.ApiVersionElement("/api/composite", 1, API_COMPOSITE_MINOR, List.of(1)),
                    new ApiVersionInfo.ApiVersionElement("/api/backups", 1, API_BACKUPS_MINOR, List.of(1))
            ));

    @APIResponse(responseCode = "200",
            description = "Current APi version and supported API versions")
    @GET
    public Response apiVersionInfo() {
        return Response.ok(API_VERSION_INFO).build();
    }
}
