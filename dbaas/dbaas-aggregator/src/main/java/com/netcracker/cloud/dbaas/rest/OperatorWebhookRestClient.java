package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;


public interface OperatorWebhookRestClient extends AutoCloseable {

    @POST
    @Path("/api/rotation/v1/notify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    void notify(RotationEventPayload payload);
}
