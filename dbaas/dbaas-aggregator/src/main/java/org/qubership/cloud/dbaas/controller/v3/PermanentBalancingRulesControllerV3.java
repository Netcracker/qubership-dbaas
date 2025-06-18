package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.DbaasApiPath;
import org.qubership.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDTO;
import org.qubership.cloud.dbaas.dto.v3.PermanentPerNamespaceRuleDeleteDTO;
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

import java.util.ArrayList;
import java.util.List;

import static org.qubership.cloud.dbaas.Constants.DB_CLIENT;
import static org.qubership.cloud.dbaas.Constants.DB_EDITOR;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;

@Slf4j
@Path(DbaasApiPath.PERMANENT_BALANCING_RULES_V3)
@Tag(
        name = "Balancing Rules Administration V3",
        description = "Allows to configure a logic of balancing logical databases over physical. ")
@Produces(MediaType.APPLICATION_JSON)
public class PermanentBalancingRulesControllerV3 {

    @Inject
    BalancingRulesService balancingRulesService;

    @Operation(summary = "V3. Add permanent namespace balancing rule",
            description = "Allows adding new permanent namespace balancing rule. Balancing rules are intended " +
                    "to define in which physical database new logical database should be created. This API " +
                    "allows add such rule for a namespace: it means that all logical databases for microservices " +
                    "in this namespace will be placed in specific physical database according to rule. Such rule " +
                    "is permanent and it won't be deleted during physical database deletion. \n +" +
                    " *WARNING! Rules can be overridden. Rule's integrity and validity is the responsibility of project(applies) side.* \n" +
                    " It means that rule doesn't merge and there can be only one version of rule. If you change configuration of previous rule and send it, then logical databases will be created by the new changed rule. Therefore, be careful before deleting or modifying the rule.")
    @APIResponses({
            @APIResponse(responseCode = "400",
                    description = "Cannot create two different rules for same namespace with different physicalDbId and same DbType"),
            @APIResponse(responseCode = "200",
                    description = "New rules created")})
    @PUT
    @RolesAllowed({DB_CLIENT, DB_EDITOR})
    @Transactional
    public Response addRule(
            @Parameter(
                    description = "List of on namespace balancing rules expected to be applied",
                    required = true)
                    List<PermanentPerNamespaceRuleDTO> request) {
        List<String> conflictingNamespaces = findConflictingNamespaces(request);
        if (!conflictingNamespaces.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Same namespaces " + conflictingNamespaces
                    + " are used for different physical databases").build();
        }
        List<PermanentPerNamespaceRuleDTO> response = balancingRulesService.savePermanentOnNamespace(request);
        return Response.ok(response).build();
    }

    @Operation(summary = "V3. Get permanent namespace balancing rule",
            description = "Get list of applied permanent balancing rules.")
    @APIResponses({
            @APIResponse(responseCode = "404",
                    description = "Rules not found"),
            @APIResponse(responseCode = "200",
                    description = "Return founded rules")})
    @GET
    @RolesAllowed({DB_CLIENT, DB_EDITOR})
    public Response getRule(
            @Parameter(
                    description = "Namespace for which the rules will be searched",
                    required = false)
            @QueryParam(NAMESPACE_PARAMETER) String namespace) {
        if (namespace != null) {
            return Response.ok(balancingRulesService.getPermanentOnNamespaceRule(namespace)).build();
        }
        return Response.ok(balancingRulesService.getPermanentOnNamespaceRule()).build();
    }

    @Operation(summary = "V3. Delete permanent namespace balancing rule",
            description = "Delete all permanent balancing rules on namespace. ")
    @APIResponses({
            @APIResponse(responseCode = "200",
                    description = "Rules deleted")})
    @DELETE
    @RolesAllowed({DB_CLIENT, DB_EDITOR})
    @Transactional
    public Response deletePermanentRules(
            @Parameter(
                    description = "List of on namespace physDb balancing rules expected to be deleted. " +
                            "If DbType is specified, only rules for this type will be deleted",
                    required = true)
                    List<PermanentPerNamespaceRuleDeleteDTO> rulesToDelete) {
        balancingRulesService.deletePermanentRules(rulesToDelete);
        return Response.ok().build();
    }

    private List<String> findConflictingNamespaces(List<PermanentPerNamespaceRuleDTO> request) {
        // if request contains equal namespaces for same dbType with different physDbIds it's conflict
        for (int i = 0; i < request.size(); i++) {
            PermanentPerNamespaceRuleDTO ruleToCheck = request.get(i);
            for (int j = i + 1; j < request.size(); j++) {
                PermanentPerNamespaceRuleDTO rule = request.get(j);
                if (ruleToCheck.getDbType().equals(rule.getDbType())) {
                    ArrayList<String> namespaces = new ArrayList<>(ruleToCheck.getNamespaces());
                    namespaces.retainAll(rule.getNamespaces());
                    if (!namespaces.isEmpty()) {
                        return namespaces;
                    }
                }
            }
        }
        return new ArrayList<>();
    }
}
