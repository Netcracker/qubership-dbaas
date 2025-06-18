package org.qubership.cloud.dbaas.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public class DbaasAdapterRestClientLoggingFilter implements ClientRequestFilter, ClientResponseFilter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        if (log.isDebugEnabled()) {

            if (requestContext.hasEntity()) {
                try {
                    var entity = requestContext.getEntity();
                    String bodyStr;

                    if (entity instanceof String entityStr) {
                        bodyStr = entityStr;
                    } else {
                        bodyStr = objectMapper.writeValueAsString(entity);
                    }

                    log.debug("Request: {} {}, body: {}", requestContext.getMethod(), requestContext.getUri(), bodyStr);
                } catch (Exception ex) {
                    log.debug("Request: {} {}, body: error during parsing body: {}", requestContext.getMethod(), requestContext.getUri(), ex.getMessage());
                }
            } else {
                log.debug("Request: {} {}, body: empty", requestContext.getMethod(), requestContext.getUri());
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

                    log.debug("Response: [{} {}] {} {}, body: {}", responseContext.getStatus(), responseContext.getStatusInfo(),
                        requestContext.getMethod(), requestContext.getUri(), bodyStr
                    );
                } catch (Exception ex) {
                    log.debug("Response: [{} {}] {} {}, body: error during parsing body: {}", responseContext.getStatus(), responseContext.getStatusInfo(),
                        requestContext.getMethod(), requestContext.getUri(), ex.getMessage()
                    );
                }
            } else {
                log.debug("Response: [{} {}] {} {}, body: empty", responseContext.getStatus(), responseContext.getStatusInfo(),
                    requestContext.getMethod(), requestContext.getUri()
                );
            }
        }
    }
}
