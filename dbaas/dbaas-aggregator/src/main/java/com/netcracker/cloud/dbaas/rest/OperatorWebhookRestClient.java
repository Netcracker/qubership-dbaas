package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;
import io.vertx.core.impl.NoStackTraceTimeoutException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.net.SocketTimeoutException;
import java.time.temporal.ChronoUnit;

@Retry(delay = 1, delayUnit = ChronoUnit.SECONDS, maxRetries = 5,
        retryOn = {SocketTimeoutException.class, NoStackTraceTimeoutException.class})
public interface OperatorWebhookRestClient extends AutoCloseable{

    @POST
    @Path("/api/rotation/v1/notify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    void notify(RotationEventPayload payload);
}
