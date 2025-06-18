package org.qubership.cloud.dbaas.controller;

import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.DeleteOrphansRequest;
import org.qubership.cloud.dbaas.dto.bluegreen.*;
import org.qubership.cloud.dbaas.entity.pg.BgDomain;
import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.BgRequestValidationException;
import org.qubership.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.cloud.dbaas.service.DbaaSHelper;
import org.qubership.cloud.dbaas.service.ProcessService;
import org.qubership.cloud.dbaas.service.processengine.processes.ProcessWrapper;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ACTIVE_STATE;
import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.Constants.IDLE_STATE;

@Slf4j
@Path(DbaasApiPath.DBAAS_BLUE_GREEN_PATH_V1)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class BlueGreenControllerV1 {

    @Inject
    BlueGreenService blueGreenService;

    @Inject
    ProcessService processService;

    @Inject
    DbaaSHelper dbaaSHelper;

    @Operation(
            summary = "Warmup namespace",
            description = "The API prepare databases in namespace to work in versioning mode. If database is versioned then will be create full copy." +
                    "If database is static will be create only new classifier")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Warmup is already done"),
            @APIResponse(responseCode = "202", description = "Warmup process has started, return trackingId "),
            @APIResponse(responseCode = "400", description = "Incorrect request"),
            @APIResponse(responseCode = "409", description = "Invalid request body"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/warmup")
    @POST
    @Transactional
    public Response warmup(@Parameter(description = "target namespace to warmup version to warmup", required = true)
                                   BgStateRequest bgStateRequest) {
        log.info("Received request to warmup {}", bgStateRequest);
        if (!isValidBgStateRequest(bgStateRequest.getBGState())) {
            throw new BgRequestValidationException("Origin namespace or peer namespace in not present");
        }
        ProcessInstanceImpl processInstance = blueGreenService.warmup(bgStateRequest.getBGState());
        AsyncResponse warmupResponse = new AsyncResponse();
        warmupResponse.setMessage("Warmup successfully done");
        if (processInstance == null) {
            return Response.ok(warmupResponse).build();
        }
        warmupResponse.setTrackingId(processInstance.getId());
        warmupResponse.setMessage(getProcessMessage(processInstance));

        return Response.accepted(warmupResponse).build();
    }

    @Operation(
            summary = "Promote")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Promote is already done"),
            @APIResponse(responseCode = "400", description = "Incorrect request"),
            @APIResponse(responseCode = "409", description = "Invalid request body"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/promote")
    @POST
    @Transactional
    public Response promote(@Parameter(description = "BG state to promote", required = true)
                                    BgStateRequest bgStateRequest) {
        log.info("Received request to promote {}", bgStateRequest);
        blueGreenService.promote(bgStateRequest);
        BlueGreenResponse blueGreenResponse = new BlueGreenResponse();
        blueGreenResponse.setMessage("promote was done");
        return Response.ok(blueGreenResponse).build();
    }

    @Operation(
            summary = "rollback")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Rollback is already done"),
            @APIResponse(responseCode = "400", description = "Incorrect request"),
            @APIResponse(responseCode = "409", description = "Invalid request body"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/rollback")
    @POST
    @Transactional
    public Response rollback(@Parameter(description = "BG state to rollback", required = true)
                                     BgStateRequest bgStateRequest) {
        log.info("Received request to rollback {}", bgStateRequest);
        blueGreenService.rollback(bgStateRequest);
        BlueGreenResponse blueGreenResponse = new BlueGreenResponse();
        blueGreenResponse.setMessage("rollback was done");
        return Response.ok(blueGreenResponse).build();
    }


    @Operation(
            summary = "Operation status",
            description = "Get operation status by trackingId")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "return operation status"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/{trackingId}/status")
    @GET
    public Response getOperationStatus(@Parameter(description = "Id to track operation", required = true)
                                       @PathParam("trackingId") String trackingId) {
        log.info("Received request to return operation status with id = {}", trackingId);
        ProcessInstanceImpl process = processService.getProcess(trackingId);
        OperationStatusResponse operationStatusResponse = new OperationStatusResponse();
        operationStatusResponse.setMessage(getProcessMessage(process));
        operationStatusResponse.setStatus(process.getState());
        operationStatusResponse.setOperationDetails(getOperationDetails(process));

        return Response.ok(operationStatusResponse).build();
    }


    @Operation(
            summary = "Terminate operation",
            description = "Terminate operation by trackingId")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Terminate operation and return status"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/{trackingId}/terminate")
    @POST
    @Transactional
    public Response terminateOperaion(@Parameter(description = "Id to track operation", required = true)
                                      @PathParam("trackingId") String trackingId) {
        log.info("Received request to terminate operation with id = {}", trackingId);
        processService.terminateProcess(trackingId);
        log.debug("terminate process with id = {}", trackingId);
        ProcessInstanceImpl process = processService.getProcess(trackingId);
        log.debug("Process after termination {}", process.getState());
        OperationStatusResponse operationStatusResponse = new OperationStatusResponse();
        operationStatusResponse.setMessage(getProcessMessage(process));
        operationStatusResponse.setStatus(process.getState());
        operationStatusResponse.setOperationDetails(getOperationDetails(process));
        return Response.ok(operationStatusResponse).build();
    }

    @NotNull
    private static List<OperationDetail> getOperationDetails(ProcessInstanceImpl process) {
        ProcessWrapper processWrapper = new ProcessWrapper(process);
        List<OperationDetail> operationDetails = new ArrayList<>();
        processWrapper.getTerminalTasks()
                .forEach(task -> {
                    AbstractDatabaseProcessObject processObject = (AbstractDatabaseProcessObject) task.getContext().get("processObject");
                    OperationDetail.OperationDetailBuilder operationBuilder = OperationDetail.builder()
                            .status(task.getState())
                            .type(processObject.getConfig().getType())
                            .taskId(task.getId())
                            .classifier(processObject.getConfig().getClassifier());

                    if (processObject instanceof CloneDatabaseProcessObject cloneDatabaseProcessObject) {
                        operationBuilder.backupId(cloneDatabaseProcessObject.getBackupId().toString())
                                .restoreId(cloneDatabaseProcessObject.getRestoreId().toString());
                    }
                    operationDetails.add(operationBuilder.build());
                });
        return operationDetails;
    }

    @NotNull
    private String getProcessMessage(ProcessInstanceImpl process) {
        String message;
        ProcessWrapper processWrapper = new ProcessWrapper(process);
        long allDbCount = processWrapper.getAllDbCount();
        long completedDbCount = processWrapper.getCompletedDbCount();
        if (completedDbCount >= allDbCount) {
            message = allDbCount + " databases were processed";
        } else {
            message = completedDbCount + " of " + allDbCount + " databases are processed";
        }
        return message;
    }

    private boolean isValidBgStateRequest(BgStateRequest.BGState bgState) {
        return bgState.getOriginNamespace() != null && bgState.getPeerNamespace() != null;
    }

    @Operation(
            summary = "Init Bg Domain",
            description = "Group namespaces into on Blue-green domain")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Blue-green domain successfully registered"),
            @APIResponse(responseCode = "400", description = "Incorrect request"),
            @APIResponse(responseCode = "409", description = "Invalid request body"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/init-domain")
    @POST
    @Transactional
    public Response initBgDomain(BgStateRequest bgStateRequest) {
        log.info("Receive request to init bg domain = {}", bgStateRequest);
        blueGreenService.initBgDomain(bgStateRequest);
        return Response.ok(new BlueGreenResponse("Success init domain")).build();
    }

    @Operation(
            summary = "Commit Bg Domain",
            description = "Group namespaces into on Blue-green domain")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Commit operation successfully done"),
            @APIResponse(responseCode = "400", description = "Incorrect request"),
            @APIResponse(responseCode = "409", description = "Invalid request body"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/commit")
    @POST
    @Transactional
    public Response commit(BgStateRequest bgStateRequest) {
        log.info("Receive request to commit = {}", bgStateRequest);
        BgStateRequest.BGStateNamespace bgRequestNamespace1 = bgStateRequest.getBGState().getOriginNamespace();
        BgStateRequest.BGStateNamespace bgRequestNamespace2 = bgStateRequest.getBGState().getPeerNamespace();
        if (!(bgRequestNamespace1.getState().equals(ACTIVE_STATE) && bgRequestNamespace2.getState().equals(IDLE_STATE) ||
                bgRequestNamespace1.getState().equals(IDLE_STATE) && bgRequestNamespace2.getState().equals(ACTIVE_STATE))) {
            throw new BgRequestValidationException(String.format("States of bgRequest must be active and idle, but there are %s and %s", bgRequestNamespace1.getState(), bgRequestNamespace2.getState()));
        }

        BgDomain domain = blueGreenService.getDomain(bgStateRequest.getBGState().getOriginNamespace().getName());
        if (domain == null) {
            throw new BgRequestValidationException("Can't find registered Blue-Green domain with requested namespace");
        }
        Optional<BgNamespace> optionalBgNamespace1 = domain.getNamespaces().stream().filter(v -> v.getNamespace().equals(bgRequestNamespace1.getName())).findFirst();
        Optional<BgNamespace> optionalBgNamespace2 = domain.getNamespaces().stream().filter(v -> v.getNamespace().equals(bgRequestNamespace2.getName())).findFirst();
        if (optionalBgNamespace1.isEmpty() || optionalBgNamespace2.isEmpty()) {
            throw new BgRequestValidationException("Request with incorrect namespaces");
        }

        BgNamespace bgNamespace1 = optionalBgNamespace1.get();
        BgNamespace bgNamespace2 = optionalBgNamespace2.get();
        if ((bgNamespace1.getState().equals(ACTIVE_STATE) && bgNamespace2.getState().equals(IDLE_STATE) ||
                bgNamespace1.getState().equals(IDLE_STATE) && bgNamespace2.getState().equals(ACTIVE_STATE))) {
            return Response.ok(new BlueGreenResponse("Commit successfully done")).build();
        }

        if (!bgNamespace1.getState().equals(ACTIVE_STATE) && !bgNamespace2.getState().equals(ACTIVE_STATE)) {
            throw new BgRequestValidationException("Blue-Green domain doesn't contain active state");
        }
        if ((!bgRequestNamespace1.getState().equals(ACTIVE_STATE) || !bgNamespace1.getVersion().equals(bgRequestNamespace1.getVersion())) &&
                (!bgRequestNamespace2.getState().equals(ACTIVE_STATE) || !bgNamespace2.getVersion().equals(bgRequestNamespace2.getVersion()))) {
            throw new BgRequestValidationException("Incorrect version for active namespace");
        }

        int markedDatabasesAsOrphan = blueGreenService.commit(bgStateRequest).size();
        return Response.ok(new BlueGreenResponse(markedDatabasesAsOrphan + " databases are marked as Orphan")).build();
    }

    @Operation(
            summary = "Get list of orphan databases",
            description = "List of databases with orphan state related to requested namespaces")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of databases"),
            @APIResponse(responseCode = "400", description = "Bad request"),
            @APIResponse(responseCode = "500", description = "Internal processing error")
    })
    @Path("/orphans")
    @POST
    public Response getOrphans(@Parameter(description = "List of namespaces in blue-green state by which orhan databases is needed to return", required = true)
                                       List<String> namespaces) {
        log.info("Receive request to get orphan databases in {}", namespaces);
        if (CollectionUtils.isEmpty(namespaces)) {
            throw new BgRequestValidationException("Should be at least one namespace");
        }
        List<DatabaseRegistry> orphans = blueGreenService.getOrphanDatabases(namespaces);
        return Response.ok(toOrphanDatabasesResponse(orphans)).build();
    }

    private List<OrphanDatabasesResponse> toOrphanDatabasesResponse(List<DatabaseRegistry> orphans) {
        return orphans.stream().map(OrphanDatabasesResponse::new).toList();
    }

    @Operation(
            summary = "Cleanup orphan databases",
            description = "Housekeeping operation which drops all databases with ORPHAN state." +
                    "WARNING! If delete=true this operation will drop databases even when DBaaS is in PROD mode")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Orphan databases successfully dropped"),
            @APIResponse(responseCode = "400", description = "Bad request"),
            @APIResponse(responseCode = "500", description = "Internal processing error")
    })
    @Path("/orphans")
    @DELETE
    @Transactional
    public Response cleanupOrphans(DeleteOrphansRequest request) {
        log.info("Receive request to drop orphan databases in {}", request.getNamespaces());
        if (CollectionUtils.isEmpty(request.getNamespaces())) {
            throw new BgRequestValidationException("Should be at least one namespace");
        }
        List<DatabaseRegistry> orphans = blueGreenService.dropOrphanDatabases(request.getNamespaces(), request.getDelete());
        return Response.ok(toOrphanDatabasesResponse(orphans)).build();
    }

    @Operation(
            summary = "Get All bg Domains",
            description = "get all registered bg domains")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Get registered bg domains"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/get-domains")
    @GET
    public Response getBgDomains() {
        log.info("receive request to get all bg domains");
        return Response.ok(blueGreenService.getDomains().stream().map(BgDomainForGet::new)).build();
    }

    @Operation(
            summary = "Get All bg Domains",
            description = "get all registered bg domains")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "registered bg domains"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/get-domains/{namespace}")
    @GET
    public Response getBgDomain(@PathParam("namespace") String namespace) {
        log.info("receive request to get all bg domains");
        return Response.ok(new BgDomainForGet(blueGreenService.getDomain(namespace))).build();
    }

    @Operation(
            summary = "List all BG domains",
            description = "list all registered BG domains")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of registered BG domains"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/list-domains")
    @GET
    public Response listBgDomains() {
        log.info("receive request to list all BG domains");
        return Response.ok(blueGreenService.getDomains().stream().map(BgDomainForList::new).toList()).build();
    }

    @Operation(
            summary = "Delete bg Domains",
            description = "destroy registered bg domain")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Blue-green domain successfully destroyed"),
            @APIResponse(responseCode = "400", description = "Incorrect request"),
            @APIResponse(responseCode = "404", description = "Bg domain not found"),
            @APIResponse(responseCode = "409", description = "Invalid request body"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/destroy-domain")
    @DELETE
    @Transactional
    public Response destroyBgDomain(BgNamespaceRequest bgNamespaceRequest) {
        log.info("receive request to destroy bg domain");
        if (dbaaSHelper.isProductionMode()) {
            throw new ForbiddenDeleteOperationException();
        }
        Set<String> listOfNamespaces = new HashSet<>(
                Set.of(bgNamespaceRequest.getOriginNamespace(), bgNamespaceRequest.getPeerNamespace()));
        blueGreenService.destroyDomain(listOfNamespaces);
        BlueGreenResponse blueGreenResponse = new BlueGreenResponse("Domain successfully destroyed");
        return Response.ok(blueGreenResponse).build();
    }
}
