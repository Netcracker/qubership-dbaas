package org.qubership.cloud.dbaas.rest;

import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import io.vertx.core.impl.NoStackTraceTimeoutException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.faulttolerance.Retry;

import java.net.SocketTimeoutException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Retry(delay = 1, delayUnit = ChronoUnit.SECONDS, maxRetries = 5,
        retryOn = {SocketTimeoutException.class, NoStackTraceTimeoutException.class})
public interface DbaasAdapterRestClient extends AutoCloseable {

    @GET
    @Path("/health")
    AdapterHealthStatus getHealth();

    @GET
    @Path("/api/v1/dbaas/adapter/{type}/physical_database")
    Response handshake(@PathParam("type") String type);

    @GET
    @Path("/api/v1/dbaas/adapter/physical_database/force_registration")
    Response forceRegistration();

    @GET
    @Path("/api/v1/dbaas/adapter/{type}/supports")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Boolean> supports(@PathParam("type") String type);

    @POST
    @Path("/api/v1/dbaas/adapter/{type}/backups/{backupId}/restore")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction restoreBackup(@PathParam("type") String type, @PathParam("backupId") String backupId,
                                @QueryParam("regenerateNames") boolean regenerateNames,
                                List<String> databases);

    @POST
    @Path("/api/v1/dbaas/adapter/{type}/backups/collect")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction collectBackup(@PathParam("type") String type,
                                @QueryParam("allowEviction") Boolean allowEviction,
                                @QueryParam("keep") String keep,
                                List<String> databases);

    @GET
    @Path("/api/v1/dbaas/adapter/{type}/backups/track/{action}/{track}")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction trackBackup(@PathParam("type") String type, @PathParam("action") String action, @PathParam("track") String track);

    @DELETE
    @Path("/api/v1/dbaas/adapter/{type}/backups/{backupId}")
    String deleteBackup(@PathParam("type") String type, @PathParam("backupId") String backupId);

    @POST
    @Path("/api/v1/dbaas/adapter/{type}/resources/bulk-drop")
    void dropDatabase(@PathParam("type") String type, List<DbResource> resources);

    @PUT
    @Path("/api/v1/dbaas/adapter/{type}/users/{username}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EnsuredUser ensureUser(@PathParam("type") String type, @PathParam("username") String username,
                           UserEnsureRequest request);

    @PUT
    @Path("/api/v1/dbaas/adapter/{type}/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EnsuredUser ensureUser(@PathParam("type") String type,
                           UserEnsureRequest request);

    @GET
    @Path("/api/v1/dbaas/adapter/{type}/databases")
    Set<String> getDatabases(@PathParam("type") String type);

    @POST
    @Path("/api/v1/dbaas/adapter/{type}/databases")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CreatedDatabase createDatabase(@PathParam("type") String type,
                                   AdapterDatabaseCreateRequest createRequest);

    @POST
    @Path("/api/v1/dbaas/adapter/{type}/describe/databases?connectionProperties&resources")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, DescribedDatabase> describeDatabases(@PathParam("type") String type,
                                                     Collection<String> databases);

    @PUT
    @Path("/api/v1/dbaas/adapter/{type}/databases/{dbName}/metadata")
    void changeMetaData(@PathParam("type") String type, @PathParam("dbName") String dbName,
                        Map<String, Object> metadata);


    @PUT
    @Path("/api/v1/dbaas/adapter/{type}/databases/{dbName}/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    String updateSettings(@PathParam("type") String type, @PathParam("dbName") String dbName,
                          UpdateSettingsAdapterRequest request);

}
