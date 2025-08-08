package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.core.error.rest.tmf.TmfError;
import org.qubership.cloud.core.error.rest.tmf.TmfErrorResponse;
import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.AccessLevel;
import lombok.CustomLog;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;

import static org.qubership.cloud.core.error.rest.tmf.TmfErrorResponse.TYPE_V1_0;
import static jakarta.ws.rs.core.Response.Status.Family.SERVER_ERROR;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
@CustomLog
public class Utils {
    static final String ERRORS_PORTAL_URL = "https://errors-portal.qubership.org/error/";
    static final String WARNING_MESSAGE = "{} happened during request to {}. Error: {}";

    static Response buildResponse(Response.Status status,
                                  Supplier<TmfErrorResponse> tmfResponseSupplier) {
        return Response.status(status).entity(tmfResponseSupplier.get()).build();
    }

    static Response buildResponse(Response.Status status,
                                  Supplier<TmfErrorResponse> tmfResponseSupplier,
                                  URI location) {
        return Response.status(status).entity(tmfResponseSupplier.get()).location(location).build();
    }

    static TmfErrorResponse.TmfErrorResponseBuilder tmfResponseBuilder(ErrorCodeException e, Response.Status status) {
        return TmfErrorResponse.builder()
                .id(e.getId())
                .code(e.getErrorCode().getCode())
                .reason(e.getErrorCode().getTitle())
                .detail(e.getDetail())
                .status(String.valueOf(status.getStatusCode()))
                .type(TYPE_V1_0);
    }

    static TmfError.TmfErrorBuilder tmfErrorBuilder(ErrorCodeException e, Response.Status status) {
        return TmfError.builder()
                .id(e.getId())
                .code(e.getErrorCode().getCode())
                .reason(e.getErrorCode().getTitle())
                .detail(e.getDetail())
                .status(String.valueOf(status.getStatusCode()));
    }

    static Response buildDefaultResponse(UriInfo requestUri, ErrorCodeException e, Response.Status status) {
        if (status.getFamily() == SERVER_ERROR) {
            log.error(WARNING_MESSAGE, e.getClass().getSimpleName(), requestUri.getPath(), e, e);
        } else {
            log.warn(WARNING_MESSAGE, e.getClass().getSimpleName(), requestUri.getPath(), e.getMessage(), e);
        }
        return buildResponse(status,
                () -> tmfResponseBuilder(e, status).build());
    }

    public static Response createTmfErrorResponse(UriInfo requestUri, ErrorCodeException e, Response.Status status, Map<String, Object> meta) {
        if (status.getFamily() == SERVER_ERROR) {
            log.error(WARNING_MESSAGE, e.getClass().getSimpleName(), requestUri.getPath(), e, e);
        } else {
            log.warn(WARNING_MESSAGE, e.getClass().getSimpleName(), requestUri.getPath(), e.getMessage(), e);
        }
        return createTmfErrorResponse(e, status, meta);
    }

    public static Response createTmfErrorResponse(ErrorCodeException e, Response.Status status, Map<String, Object> meta) {
        TmfErrorResponse.TmfErrorResponseBuilder builder = TmfErrorResponse.builder()
                .id(e.getId())
                .code(e.getErrorCode().getCode())
                .reason(e.getErrorCode().getTitle())
                .detail(e.getDetail())
                .status(String.valueOf(status.getStatusCode()))
                .meta(meta)
                .type(TYPE_V1_0)
                .referenceError(ERRORS_PORTAL_URL + e.getErrorCode().getCode())
                .schemaLocation(ERRORS_PORTAL_URL + "schema/" + TYPE_V1_0);
        return buildResponse(status, () -> builder.build());
    }


}
