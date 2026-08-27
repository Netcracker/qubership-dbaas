package com.netcracker.cloud.dbaas.testapp.quarkus;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.Map;

/**
 * Maps a database failure to a 500 with a generic JSON body, matching the Go/Spring services'
 * error shape {@code {"error": ...}}.
 */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<SQLException> {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class);

    @Override
    public Response toResponse(SQLException e) {
        LOG.error("Request failed", e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("error", "request failed"))
                .build();
    }
}
