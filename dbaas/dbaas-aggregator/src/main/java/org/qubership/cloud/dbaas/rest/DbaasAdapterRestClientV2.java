package org.qubership.cloud.dbaas.rest;

import org.qubership.cloud.dbaas.dto.*;
import org.qubership.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserAdapterRequest;
import org.qubership.cloud.dbaas.dto.v3.UserEnsureRequestV3;
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
public interface DbaasAdapterRestClientV2 extends AutoCloseable {

    @GET
    @Path("/health")
    AdapterHealthStatus getHealth();

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/physical_database")
    Response handshake(@PathParam("type") String type);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/supports")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Boolean> supports(@PathParam("type") String type);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/{backupId}/restoration")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction restoreBackup(@PathParam("type") String type, @PathParam("backupId") String backupId,
                                RestoreRequest restoreRequest);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/{backupId}/restore")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction restoreBackup(@PathParam("type") String type, @PathParam("backupId") String backupId,
                                @QueryParam("regenerateNames") boolean regenerateNames,
                                List<String> databases);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/backups/collect")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction collectBackup(@PathParam("type") String type,
                                @QueryParam("allowEviction") Boolean allowEviction,
                                @QueryParam("keep") String keep,
                                List<String> databases);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/backups/track/{action}/{track}")
    @Produces(MediaType.APPLICATION_JSON)
    TrackedAction trackBackup(@PathParam("type") String type, @PathParam("action") String action, @PathParam("track") String track);

    @DELETE
    @Path("/api/v2/dbaas/adapter/{type}/backups/{backupId}")
    String deleteBackup(@PathParam("type") String type, @PathParam("backupId") String backupId);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/resources/bulk-drop")
    Response dropResources(@PathParam("type") String type, List<DbResource> resources);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users/{username}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EnsuredUser ensureUser(@PathParam("type") String type, @PathParam("username") String username,
                           UserEnsureRequest request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users/{username}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EnsuredUser ensureUser(@PathParam("type") String type, @PathParam("username") String username,
                           UserEnsureRequestV3 request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EnsuredUser ensureUser(@PathParam("type") String type,
                           UserEnsureRequestV3 request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    EnsuredUser createUser(@PathParam("type") String type,
                           GetOrCreateUserAdapterRequest request);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/users/restore-password")
    @Consumes(MediaType.APPLICATION_JSON)
    Response restorePassword(@PathParam("type") String type,
                             RestorePasswordsAdapterRequest request);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/databases/{dbName}/metadata")
    void changeMetaData(@PathParam("type") String type, @PathParam("dbName") String dbName,
                        Map<String, Object> metadata);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/describe/databases")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, DescribedDatabase> describeDatabases(@PathParam("type") String type,
                                                     @QueryParam("connectionProperties") boolean connectionProperties,
                                                     @QueryParam("resources") boolean resources,
                                                     Collection<String> databases);

    @GET
    @Path("/api/v2/dbaas/adapter/{type}/databases")
    Set<String> getDatabases(@PathParam("type") String type);

    @POST
    @Path("/api/v2/dbaas/adapter/{type}/databases")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CreatedDatabaseV3 createDatabase(@PathParam("type") String type,
                                     AdapterDatabaseCreateRequest createRequest);

    @PUT
    @Path("/api/v2/dbaas/adapter/{type}/databases/{dbName}/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    String updateSettings(@PathParam("type") String type, @PathParam("dbName") String dbName,
                          UpdateSettingsAdapterRequest request);
}
