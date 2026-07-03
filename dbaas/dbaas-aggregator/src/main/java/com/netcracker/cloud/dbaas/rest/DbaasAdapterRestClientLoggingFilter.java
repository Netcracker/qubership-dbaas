package com.netcracker.cloud.dbaas.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.netcracker.cloud.dbaas.logging.StructuredLog;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public class DbaasAdapterRestClientLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (log.isDebugEnabled()) {
            var authScheme = requestContext.getHeaders()
                    .getOrDefault("Authorization", List.of(""))
                    .toString()
                    .toLowerCase()
                    .contains("bearer") ? "M2M" : "Basic";
            if (requestContext.hasEntity()) {
                try {
                    var entity = requestContext.getEntity();
                    String bodyStr;

                    if (entity instanceof String entityStr) {
                        bodyStr = entityStr;
                    } else {
                        bodyStr = objectMapper.writeValueAsString(entity);
                    }

                    StructuredLog.debug(log, "Adapter REST request",
                            "method", requestContext.getMethod(), "uri", requestContext.getUri(),
                            "auth", authScheme, "body", bodyStr);
                } catch (Exception ex) {
                    StructuredLog.debug(log, "Adapter REST request body parse failed",
                            "method", requestContext.getMethod(), "uri", requestContext.getUri(),
                            "auth", authScheme, "error", ex.getMessage());
                }
            } else {
                StructuredLog.debug(log, "Adapter REST request",
                        "method", requestContext.getMethod(), "uri", requestContext.getUri(),
                        "auth", authScheme, "body", "empty");
            }
        }
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
        if (log.isDebugEnabled()) {

            if (responseContext.hasEntity()) {
                try {
                    var bodyStr = IOUtils.toString(responseContext.getEntityStream(), StandardCharsets.UTF_8);

                    responseContext.setEntityStream(IOUtils.toInputStream(bodyStr, StandardCharsets.UTF_8));

                    StructuredLog.debug(log, "Adapter REST response",
                            "status", responseContext.getStatus(), "statusInfo", responseContext.getStatusInfo(),
                            "method", requestContext.getMethod(), "uri", requestContext.getUri(), "body", bodyStr);
                } catch (Exception ex) {
                    StructuredLog.debug(log, "Adapter REST response body parse failed",
                            "status", responseContext.getStatus(), "statusInfo", responseContext.getStatusInfo(),
                            "method", requestContext.getMethod(), "uri", requestContext.getUri(),
                            "error", ex.getMessage());
                }
            } else {
                StructuredLog.debug(log, "Adapter REST response",
                        "status", responseContext.getStatus(), "statusInfo", responseContext.getStatusInfo(),
                        "method", requestContext.getMethod(), "uri", requestContext.getUri(), "body", "empty");
            }
        }
    }
}
