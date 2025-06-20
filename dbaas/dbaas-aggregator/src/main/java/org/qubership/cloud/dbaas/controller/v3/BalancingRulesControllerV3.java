package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.OnMicroserviceRuleRequest;
import org.qubership.cloud.dbaas.dto.RuleRegistrationRequest;
import org.qubership.cloud.dbaas.dto.v3.DebugRulesDbTypeData;
import org.qubership.cloud.dbaas.dto.v3.DebugRulesRequest;
import org.qubership.cloud.dbaas.dto.v3.ValidateRulesResponse;
import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.exceptions.OnMicroserviceBalancingRuleException;
import org.qubership.cloud.dbaas.service.BalancingRulesService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.DbaasApiPath.BALANCING_RULES_V3;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;

@Slf4j
@Path(BALANCING_RULES_V3)
@Tag(
        name = "Balancing Rules Administration V3",
        description = "Allows to configure a logic of balancing logical databases over physical. ")
@Produces(MediaType.APPLICATION_JSON)
public class BalancingRulesControllerV3 {

    @Inject
    BalancingRulesService balancingRulesService;

    @Operation(summary = "V3. On namespace physDb balancing rule",
            description = "There are no changes in comparison with version 1. " +
                    "Auto balancing rules allows configure behavior of DbaaS when a physical " +
                    "database of some specific type is choosen for new logical database. " +
                    "This rule currently works for new databases only, no migration " +
                    "of logical databases between physical databases is supported yet.")
    @APIResponses({
            @APIResponse(responseCode = "409",
                    description = "Cannot create two different rules for same type with same order"),
            @APIResponse(responseCode = "201",
                    description = "New rule created"),
            @APIResponse(responseCode = "200",
                    description = "Existing rule changed")})
    @Path("/balancing/rules/{ruleName}")
    @PUT
    @RolesAllowed(DB_CLIENT)
    @Transactional
    public Response addRule(
            @Parameter(
                    description = "Namespace where the rule will be placed, " +
                            "each rule must have a namespace. " +
                            "Rules works only on logical databases " +
                            "created in the same namespace where they have been created.",
                    required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace,
            @Parameter(
                    description = "Name of the rule used as an identifier",
                    required = true)
            @PathParam("ruleName") String ruleName,
            RuleRegistrationRequest request) {
        boolean isUpdated = balancingRulesService.saveOnNamespace(ruleName, namespace, request);
        return isUpdated ?
                Response.ok().build() :
                Response.created(
                        URI.create(DbaasApiPath.DBAAS_PATH_V3 + "/" +
                                namespace + "/physical_databases/balancing/rules/" + ruleName)).build();
    }


    @Operation(summary = "V3. On microservice physDb balancing rule.",
            description = "Allows adding balancing rules for microservices.  Balancing rules are intended " +
                    "to define in which physical database new logical database should be created. This API " +
                    "allows adding such rules for each microservice, or for group of microservices separately. \n" +
                    " *WARNING! Rules can be overridden. Rule's integrity and validity is the responsibility of project(applies) side.* \n" +
                    " It means that rule doesn't merge and there can be only one version of rule. If you change configuration of previous rule and send it, then logical databases will be created by the new changed rule. Therefore, be careful before deleting or modifying the rule.")
    @APIResponses({
            @APIResponse(responseCode = "201",
                    description = "New rule created"),
            @APIResponse(responseCode = "400",
                    description = "Received request with wrong body")})
    @Path("/rules/onMicroservices")
    @PUT
    @RolesAllowed(DB_CLIENT)
    @Transactional
    public Response addOnMicroservicePhysicalRule(
            List<OnMicroserviceRuleRequest> onMicroserviceRuleRequest,
            @Parameter(
                    description = "Namespace where the rule will be placed, " +
                            "each rule must have a namespace. " +
                            "Rules works only on logical databases " +
                            "created in the same namespace where they have been created.",
                    required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Received request on create per microservices physical rule. Request body {} and namespace {}", onMicroserviceRuleRequest, namespace);
        List<PerMicroserviceRule> rules = balancingRulesService.addRuleOnMicroservice(onMicroserviceRuleRequest, namespace);
        return Response.status(Response.Status.CREATED).entity(rules).build();
    }

    @Operation(summary = "V3. Get on microservice physical database balancing rules.",
        description = "Allows getting physical database balancing rules for microservices.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Gotten on microservice physical database balancing rules"),
        @APIResponse(responseCode = "500", description = "Unknown error which may be related with internal work of DbaaS.")
    })
    @Path("/rules/onMicroservices")
    @GET
    @RolesAllowed(DB_CLIENT)
    public Response getOnMicroservicePhysicalDatabaseBalancingRules(
        @Parameter(
            description = """
                Namespace where the rule is placed, each rule has a namespace.
                Rules works only on logical databases created in the same namespace where they have been created.""",
            required = true)
        @PathParam(NAMESPACE_PARAMETER) String namespace) {

        log.info("Received request to get on microservice physical  database balancing rules. Namespace {}", namespace);
        var onMicroserviceBalancingRules = balancingRulesService.getOnMicroserviceBalancingRules(namespace);
        return Response.ok(onMicroserviceBalancingRules).build();
    }

    @Operation(summary = "Validation for microservices' balancing rules.",
            description = "This API receives JSON-configs with rules for microservices and returns in response " +
                    "mapping label to physical db (whether all mentioned lables exist), indicates errors if any. " +
                    "Response also contains information about default physical databases for each db type.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Schema is valid"),
            @APIResponse(responseCode = "400", description = "Schema is not valid")})
    @Path("/rules/onMicroservices/validation")
    @PUT
    @RolesAllowed(DB_CLIENT)
    public Response validateBalancingRules(
            @Parameter(
                    description = "List of rules on microservices to validate",
                    required = true)
                    List<OnMicroserviceRuleRequest> onMicroserviceRuleRequest,
            @Parameter(
                    description = "Namespace where the rule is expected to be placed, " +
                            "each rule must have a namespace. " +
                            "Rules works only on logical databases " +
                            "created in the same namespace where they have been created.",
                    required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace) {
        log.info("Received request to validate microservices physical rule. Request body {} and namespace {}", onMicroserviceRuleRequest, namespace);
        ValidateRulesResponse response;
        try {
            response = balancingRulesService.validateMicroservicesRules(onMicroserviceRuleRequest, namespace);
        } catch (OnMicroserviceBalancingRuleException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getDetail()).build();
        }
        return Response.ok(response).build();
    }

    @Operation(summary = "Debug for microservices' balancing rules.",
            description = "This API receives JSON-configs with rules and list of microservices to check and returns in response " +
                    "a mapping what physical database is going to be assigned to each microservice from the request based " +
                    "on the balancing rules from the request and the existing rules in DBaaS. Response will also contain " +
                    "a list of labels for the assigned physical database to help analyze which rule was applied.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Return result of rules evaluation"),
            @APIResponse(responseCode = "500", description = "Error happened when processing rules")})
    @Path("/rules/debug")
    @POST
    @RolesAllowed(DB_CLIENT)
    public Response debugBalancingRules(
            @Parameter(description = "Request with rules and microservices for debugging", required = true)
                    DebugRulesRequest debugRulesRequest,
            @Parameter(description = "Namespace where the rule is expected to be placed, each rule must have a namespace. " +
                    "Rules works only on logical databases created in the same namespace where they have been created.",
                    required = true)
            @PathParam(NAMESPACE_PARAMETER) String namespace) {
        Map<String, Map<String, DebugRulesDbTypeData>> response = balancingRulesService.debugBalancingRules(debugRulesRequest.getRules(), namespace, debugRulesRequest.getMicroservices());
        return Response.ok(response).build();
    }

}
