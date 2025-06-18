package org.qubership.cloud.dbaas.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.DeclarativeCompositeRequestDTO;
import org.qubership.cloud.dbaas.dto.RolesRegistrationRequest;
import org.qubership.cloud.dbaas.dto.Source;
import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.declarative.*;
import org.qubership.cloud.dbaas.exceptions.DeclarativeConfigurationValidationException;
import org.qubership.cloud.dbaas.service.DatabaseRolesService;
import org.qubership.cloud.dbaas.service.DeclarativeDbaasCreationService;
import org.qubership.cloud.dbaas.service.ProcessService;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
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

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.qubership.cloud.dbaas.Constants.DATABASE_DECLARATION_CONFIG_TYPE;
import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.Constants.DB_POLICY_CONFIG_TYPE;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static org.qubership.cloud.dbaas.service.processengine.Const.UPDATE_BG_STATE_TASK;

@Path(DbaasApiPath.DBAAS_DECLARATIVE)
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
@Deprecated
@RolesAllowed(DB_CLIENT)
public class DeclarativeController {

    private static final String CONFIG_KIND_FIELD_NAME = "kind";
    private static final String TRIM_QUOTES_REGEX = "\"";

    @Inject
    DatabaseRolesService databaseRolesService;
    @Inject
    DeclarativeDbaasCreationService dbaasCreationService;
    @Inject
    ProcessService processService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Path("/namespaces/{" + NAMESPACE_PARAMETER + "}/service/{serviceName}")
    @POST
    @Transactional
    public Response declarativeSettings(@PathParam(NAMESPACE_PARAMETER) String namespace,
                                        @PathParam("serviceName") String serviceName,
                                        String declarativeDatabaseRequest) {

        log.info("REQUEST {}", declarativeDatabaseRequest);
        DeclarativeCompositeRequestDTO compositeRequestDTO = parseIncomingRequest(declarativeDatabaseRequest);

        DeclarativeCreationResponse declarativeCreationResponse = new DeclarativeCreationResponse();

        RolesRegistrationRequest rolesRequest = compositeRequestDTO.getRolesRegistrationRequest();
        if (rolesRequest != null && (rolesRequest.getServices() != null || rolesRequest.getPolicy() != null)) {
            log.info("Receive request to register role on service={} in namespace={} with body={}", serviceName, namespace, rolesRequest);
            databaseRolesService.saveRequestedRoles(namespace, serviceName, rolesRequest);
            DbPolicyResponse dbPolicyResponse = new DbPolicyResponse("requestedPolicies registered", "created");
            declarativeCreationResponse.setDbPolicy(dbPolicyResponse);
            log.info("Roles were saved successfully");
        }
        DeclarativeDatabaseCreationRequest dbCreationRequest = compositeRequestDTO.getDatabaseCreationRequest();
        if (dbCreationRequest != null && (dbCreationRequest.getDeclarations() != null && !dbCreationRequest.getDeclarations().isEmpty())) {
            log.info("Receive request to start databases config processing on service={} in namespace={} with body={}", serviceName, namespace, dbCreationRequest);
            ArrayList<AbstractDatabaseProcessObject> processObjects = dbaasCreationService.saveDeclarativeDatabase(namespace, serviceName, dbCreationRequest.getDeclarations());
            ProcessInstanceImpl processInstance = dbaasCreationService.startProcessInstance(namespace, processObjects);
            log.info("Declarative databases creation config were processed successfully");
            declarativeCreationResponse = addDatabaseDeclarationToResponse(declarativeCreationResponse, processInstance.getId());
            return Response.accepted(declarativeCreationResponse).build();
        }
        return Response.ok(declarativeCreationResponse).build();
    }

    @Operation(summary = "Operation status",
            description = "Get operation status by trackingId")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Return operation status"),
            @APIResponse(responseCode = "404", description = "Not found operation status"),
            @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/status/{trackingId}")
    @GET
    public Response getOperationStatus(@Parameter(description = "Id to track operation", required = true)
                                       @PathParam("trackingId") String trackingId) {

        DeclarativeCreationResponse declarativeCreationResponse = new DeclarativeCreationResponse();
        DbPolicyResponse dbPolicyResponse = new DbPolicyResponse("requestedPolicies registered", "created");
        declarativeCreationResponse.setDbPolicy(dbPolicyResponse);
        declarativeCreationResponse = addDatabaseDeclarationToResponse(declarativeCreationResponse, trackingId);
        if (TaskState.IN_PROGRESS.equals(declarativeCreationResponse.getState())) {
            return Response.accepted(declarativeCreationResponse).build();
        }
        return Response.ok(declarativeCreationResponse).build();

    }

    private DeclarativeCreationResponse addDatabaseDeclarationToResponse(DeclarativeCreationResponse declarativeCreationResponse, String processId) {
        ProcessInstanceImpl process = processService.getProcess(processId);
        DbDeclarationResponse dbDeclarationResponse = new DbDeclarationResponse();

        List<SpecDeclarativeResponseItem> specDeclarative = getSpecDeclarativeRepsponseItems(process);
        TaskState generalStatus = process.getState();
        dbDeclarationResponse.setState(generalStatus);
        dbDeclarationResponse.setSpec(specDeclarative);
        declarativeCreationResponse.setDatabaseDeclaration(dbDeclarationResponse);
        declarativeCreationResponse.setState(dbDeclarationResponse.getState());
        if (TaskState.NOT_STARTED.equals(generalStatus) || TaskState.IN_PROGRESS.equals(generalStatus)) {
            declarativeCreationResponse.setTrackingId(process.getId());
        }
        return declarativeCreationResponse;
    }

    @NotNull
    private static List<SpecDeclarativeResponseItem> getSpecDeclarativeRepsponseItems(ProcessInstanceImpl process) {
        List<SpecDeclarativeResponseItem> specDeclarative = new ArrayList<>();

        process.getTasks().forEach(task -> {
            if (!UPDATE_BG_STATE_TASK.equals(task.getName())) {
                SpecDeclarativeResponseItem specDeclarativeResponseItem = new SpecDeclarativeResponseItem();
                AbstractDatabaseProcessObject config = (AbstractDatabaseProcessObject) task.getContext().get("processObject");
                specDeclarativeResponseItem.setType(config.getConfig().getType());
                specDeclarativeResponseItem.setClassifier(config.getConfig().getClassifier());
                specDeclarativeResponseItem.setStatus(String.valueOf(task.getState()));
                specDeclarativeResponseItem.setLazy(false);
                specDeclarativeResponseItem.setInstantiationApproach(config.getConfig().getInstantiationApproach());
                specDeclarative.add(specDeclarativeResponseItem);
            }
        });
        return specDeclarative;
    }


    @NotNull
    private DeclarativeCompositeRequestDTO parseIncomingRequest(String declarativeDatabaseRequest) {
        DeclarativeCompositeRequestDTO compositeRequestDTO = new DeclarativeCompositeRequestDTO();
        try {
            JsonNode parsedJsonTree = objectMapper.readTree(declarativeDatabaseRequest);
            if (parsedJsonTree.isArray()) {
                for (JsonNode node : parsedJsonTree) {
                    processJsonForNode(node, compositeRequestDTO);
                }
            } else {
                processJsonForNode(parsedJsonTree, compositeRequestDTO);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.error("Error during parsing JSON declarative configuration: {}", e.getMessage());
            throw new DeclarativeConfigurationValidationException(e.getMessage());
        }

        return compositeRequestDTO;
    }

    private void processJsonForNode(JsonNode jsonNode, DeclarativeCompositeRequestDTO compositeRequestDTO) {
        String configKind = String.valueOf(jsonNode.get(CONFIG_KIND_FIELD_NAME)).replace(TRIM_QUOTES_REGEX, "");
        if (configKind.equals(DATABASE_DECLARATION_CONFIG_TYPE)) {
            compositeRequestDTO.setDatabaseCreationRequest(objectMapper.convertValue(jsonNode, DeclarativeDatabaseCreationRequest.class));
        } else if (configKind.equalsIgnoreCase(DB_POLICY_CONFIG_TYPE)) {
            compositeRequestDTO.setRolesRegistrationRequest(objectMapper.convertValue(jsonNode, RolesRegistrationRequest.class));
        } else {
            throw new DeclarativeConfigurationValidationException(configKind, Source.builder().build());
        }
    }
}
