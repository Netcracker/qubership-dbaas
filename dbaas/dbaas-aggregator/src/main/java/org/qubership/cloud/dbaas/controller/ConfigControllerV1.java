package org.qubership.cloud.dbaas.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.conigs.DeclarativeConfig;
import org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse;
import org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition;
import org.qubership.cloud.dbaas.dto.conigs.RolesRegistration;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import org.qubership.cloud.dbaas.dto.declarative.DeclarativePayload;
import org.qubership.cloud.dbaas.dto.declarative.OperationStatusExtendedResponse;
import org.qubership.cloud.dbaas.dto.declarative.OperationStatusExtendedResponse.OperationDetails;
import org.qubership.cloud.dbaas.dto.declarative.OperationStatusExtendedResponse.TaskDetails;
import org.qubership.cloud.dbaas.exceptions.DeclarativeConfigurationValidationException;
import org.qubership.cloud.dbaas.exceptions.TrackingIdNotFoundException;
import org.qubership.cloud.dbaas.service.DatabaseRolesService;
import org.qubership.cloud.dbaas.service.DeclarativeDbaasCreationService;
import org.qubership.cloud.dbaas.service.ProcessService;
import org.qubership.cloud.dbaas.service.processengine.processes.ProcessWrapper;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.DB_CREATED;
import static org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.ROLES_CREATED;
import static org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.VALIDATED;

@Slf4j
@Path(DbaasApiPath.DBAAS_CONFIGS_V1)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DB_CLIENT)
public class ConfigControllerV1 {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    DatabaseRolesService databaseRolesService;
    @Inject
    DeclarativeDbaasCreationService dbaasCreationService;
    @Inject
    ProcessService processService;

    @Operation(summary = "Apply config",
            description = "Apply declarative config")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Operation completed successfully", content = @Content(schema = @Schema(implementation = DeclarativeResponse.class))),
            @APIResponse(responseCode = "202", description = "Asynchronous execution started", content = @Content(schema = @Schema(implementation = DeclarativeResponse.class))),
            @APIResponse(responseCode = "400", description = "Validation error"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS")
    })
    @POST
    @Path("/apply")
    public Response applyConfigs(String request) {
        log.info("Request {}", request);
        DeclarativePayload declarativePayload = parseAndValidateRequest(request);

        DeclarativeResponse declarativeResponse = new DeclarativeResponse();
        declarativeResponse.getConditions().add(new Condition(VALIDATED, TaskState.COMPLETED));

        String namespace = declarativePayload.getMetadata().getNamespace();
        String serviceName = declarativePayload.getMetadata().getMicroserviceName();
        DeclarativeConfig spec = declarativePayload.getSpec();
        if (spec instanceof RolesRegistration rolesRegistration) {
            databaseRolesService.saveRequestedRoles(namespace, serviceName, rolesRegistration);
            declarativeResponse.setStatus(TaskState.COMPLETED);
            declarativeResponse.getConditions().add(new Condition(ROLES_CREATED, TaskState.COMPLETED));
        } else if (spec instanceof DatabaseDeclaration databaseDeclaration) {
            processDbCreation(databaseDeclaration, namespace, serviceName, declarativeResponse);
        }

        Response response;
        if (declarativeResponse.getTrackingId() != null) {
            response = Response.accepted().entity(declarativeResponse).build();
        } else {
            response = Response.ok(declarativeResponse).build();
        }
        log.info("Response {}", response);
        return response;
    }

    private DeclarativePayload parseAndValidateRequest(String request) {
        try {
            JsonNode requestTree = objectMapper.readTree(request);
            DeclarativePayload declarativePayload = objectMapper.readValue(requestTree.traverse(), DeclarativePayload.class);

            if (StringUtils.isBlank(declarativePayload.getMetadata().getNamespace())) {
                throw new IllegalArgumentException("Empty namespace in metadata");
            } else if (StringUtils.isBlank(declarativePayload.getMetadata().getMicroserviceName())) {
                throw new IllegalArgumentException("Empty microservice name in metadata");
            } else if (StringUtils.isBlank(declarativePayload.getSubKind())) {
                throw new IllegalArgumentException("Empty SubKind");
            }

            JsonNode spec = requestTree.get("spec");
            if (spec == null || spec.isEmpty()) {
                throw new IllegalArgumentException("Empty spec");
            }
            if (DATABASE_DECLARATION_CONFIG_TYPE.equalsIgnoreCase(declarativePayload.getSubKind())) {
                declarativePayload.setSpec(objectMapper.readValue(spec.traverse(), DatabaseDeclaration.class));
            } else if (DB_POLICY_CONFIG_TYPE.equalsIgnoreCase(declarativePayload.getSubKind())) {
                declarativePayload.setSpec(objectMapper.readValue(spec.traverse(), RolesRegistration.class));
            } else {
                throw new IllegalArgumentException("Unknown SubKind value: %s".formatted(declarativePayload.getSubKind()));
            }
            return declarativePayload;
        } catch (Exception e) {
            log.error("Error during parsing declarative configuration: {}", e.getMessage());
            throw new DeclarativeConfigurationValidationException(e.getMessage());
        }
    }

    @Operation(summary = "Operation status",
            description = "Get operation status by trackingId")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Return operation status", content = @Content(schema = @Schema(implementation = DeclarativeResponse.class))),
            @APIResponse(responseCode = "404", description = "Not found operation status"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS")
    })
    @GET
    @Path("/operation/{trackingId}/status")
    public Response getOperationStatus(@Parameter(description = "Id to track operation", required = true)
                                       @PathParam("trackingId") String trackingId) {
        ProcessInstanceImpl process = processService.getProcess(trackingId);
        if (process == null) {
            throw new TrackingIdNotFoundException(trackingId);
        }

        TaskState processState = process.getState();
        DeclarativeResponse declarativeResponse = new DeclarativeResponse();
        declarativeResponse.setStatus(processState);
        declarativeResponse.getConditions().add(new Condition(VALIDATED, TaskState.COMPLETED));
        declarativeResponse.getConditions().add(createDbCreationCondition(process));
        return Response.ok(declarativeResponse).build();
    }

    @Operation(summary = "Operation status",
            description = "Get operation status by trackingId")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Return operation status", content = @Content(schema = @Schema(implementation = OperationStatusExtendedResponse.class))),
            @APIResponse(responseCode = "404", description = "Not found operation status"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @GET
    @Path("/operation/{trackingId}/extendedTroubleshootingInfo")
    public Response getExtendedTroubleshootingInfo(@Parameter(description = "Id to track operation", required = true)
                                                   @PathParam("trackingId") String trackingId) {
        ProcessInstanceImpl process = processService.getProcess(trackingId);
        if (process == null) {
            throw new TrackingIdNotFoundException(trackingId);
        }

        OperationStatusExtendedResponse operationStatusResponse = new OperationStatusExtendedResponse();
        operationStatusResponse.setStatus(process);
        operationStatusResponse.setMessage(getExtendedProcessMessage(process));
        operationStatusResponse.setOperationDetails(getExtendedOperationDetails(process));
        return Response.ok(operationStatusResponse).build();
    }

    @Operation(
            summary = "Terminate operation",
            description = "Terminate operation by trackingId")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Operation completed successfully"),
            @APIResponse(responseCode = "404", description = "Incorrect trackingID"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS")
    })
    @POST
    @Path("/operation/{trackingId}/terminate")
    @Transactional
    public Response terminateOperation(@Parameter(description = "Id to track operation", required = true)
                                       @PathParam("trackingId") String trackingId) {
        log.info("Received request to terminate operation with id = {}", trackingId);
        ProcessInstanceImpl process = processService.getProcess(trackingId);
        if (process == null) {
            throw new TrackingIdNotFoundException(trackingId);
        }

        processService.terminateProcess(trackingId);
        return Response.noContent().build();
    }

    private void processDbCreation(DatabaseDeclaration databaseDeclaration, String namespace, String serviceName, DeclarativeResponse declarativeResponse) {
        ArrayList<AbstractDatabaseProcessObject> processObjects =
                dbaasCreationService.saveDeclarativeDatabase(namespace, serviceName, List.of(databaseDeclaration));
        if (!processObjects.isEmpty()) {
            ProcessInstanceImpl process = dbaasCreationService.startProcessInstance(namespace, processObjects);
            TaskState processState = process.getState();
            declarativeResponse.setTrackingId(process.getId());
            declarativeResponse.setStatus(processState);
            declarativeResponse.getConditions().add(new Condition(DB_CREATED, processState, getProcessReason(process), null));
        } else {
            declarativeResponse.setStatus(TaskState.COMPLETED);
            declarativeResponse.getConditions().add(new Condition(DB_CREATED, TaskState.COMPLETED));
        }
    }

    @NotNull
    private String getProcessReason(ProcessInstanceImpl process) {
        String reason;
        ProcessWrapper processWrapper = new ProcessWrapper(process);
        long allDbCount = processWrapper.getAllDbCount();
        long completedDbCount = processWrapper.getCompletedDbCount();
        if (completedDbCount >= allDbCount) {
            reason = "All " + allDbCount + " databases were processed";
        } else {
            reason = completedDbCount + " of " + allDbCount + " databases are processed";
        }
        return reason;
    }

    private Condition createDbCreationCondition(ProcessInstanceImpl process) {
        Condition condition;
        TaskState processState = process.getState();
        if (!TaskState.FAILED.equals(processState)) {
            condition = new Condition(DB_CREATED, processState, getProcessReason(process), getProcessMessage(process));
        } else {
            condition = new Condition(DB_CREATED, processState, getFailedProcessReason(process), getFailedProcessMessage(process));
        }
        return condition;
    }

    @NotNull
    private String getFailedProcessReason(ProcessInstanceImpl process) {
        List<TaskInstanceImpl> failedTasks = new ProcessWrapper(process).getFailedTasks();
        if (!failedTasks.isEmpty()) {
            TaskInstanceImpl task = failedTasks.getFirst();
            AbstractDatabaseProcessObject processObject = (AbstractDatabaseProcessObject) task.getContext().get("processObject");
            return String.format("Error while processing DB with classifier %s, type %s",
                    processObject.getConfig().getClassifier(), processObject.getConfig().getType());
        } else {
            return "Internal error";
        }
    }

    @Nullable
    private String getFailedProcessMessage(ProcessInstanceImpl process) {
        List<TaskInstanceImpl> failedTasks = new ProcessWrapper(process).getFailedTasks();
        return failedTasks.isEmpty() ? null : getTaskStateDescription(failedTasks.getFirst());
    }

    private String getProcessMessage(ProcessInstanceImpl process) {
        String message;
        ProcessWrapper processWrapper = new ProcessWrapper(process);
        List<TaskInstanceImpl> waitingTasks = processWrapper.getWaitingTasks();
        if (!TaskState.IN_PROGRESS.equals(process.getState())) {
            message = null;
        } else if (waitingTasks.isEmpty()) {
            message = "In progress";
        } else {
            TaskInstanceImpl waitingTask = waitingTasks.getFirst();
            message = "Waiting for resources: " + getTaskStateDescription(waitingTask);
        }
        return message;
    }

    private static String getTaskStateDescription(TaskInstanceImpl task) {
        String stateDescription = (String) task.getContext().get("stateDescription");
        if (stateDescription != null) {
            return stateDescription;
        } else if (TaskState.NOT_STARTED.equals(task.getState())) {
            return "Not started";
        } else if (TaskState.IN_PROGRESS.equals(task.getState())) {
            return "Scheduled for execution";
        } else {
            return "Unknown";
        }
    }

    @NotNull
    private String getExtendedProcessMessage(ProcessInstanceImpl process) {
        String message;
        ProcessWrapper processWrapper = new ProcessWrapper(process);
        long allTasksCount = processWrapper.getAllTasksCount();
        long completedTasksCount = processWrapper.getCompletedTasksCount();
        List<TaskInstanceImpl> failedTasks = processWrapper.getFailedTasks();
        if (!failedTasks.isEmpty()) {
            message = "Failed tasks: ";
            message += failedTasks.stream()
                    .map(TaskInstanceImpl::getId)
                    .collect(Collectors.joining(", "));
        } else if (completedTasksCount >= allTasksCount) {
            message = "All " + allTasksCount + " tasks were processed";
        } else {
            message = completedTasksCount + " of " + allTasksCount + " tasks are processed";
        }
        return message;
    }

    @NotNull
    private static OperationDetails getExtendedOperationDetails(ProcessInstanceImpl process) {
        ProcessWrapper processWrapper = new ProcessWrapper(process);
        List<TaskDetails> tasksDetails = new ArrayList<>();
        processWrapper.getConfigTasks().forEach(task -> {
            AbstractDatabaseProcessObject processObject = (AbstractDatabaseProcessObject) task.getContext().get("processObject");
            TaskDetails.TaskDetailsBuilder operationBuilder = TaskDetails.builder()
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .state(new TaskDetails.OperationState(task, getTaskStateDescription(task)))
                    .classifier(processObject.getConfig().getClassifier())
                    .type(processObject.getConfig().getType());

            if (processObject instanceof CloneDatabaseProcessObject cloneDatabaseProcessObject) {
                operationBuilder.backupId(cloneDatabaseProcessObject.getBackupId().toString())
                        .restoreId(cloneDatabaseProcessObject.getRestoreId().toString());
            }
            tasksDetails.add(operationBuilder.build());
        });
        return new OperationDetails(tasksDetails);
    }
}