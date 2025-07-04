package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.conigs.RolesRegistration;
import org.qubership.cloud.dbaas.dto.v3.UserRolesServices;
import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;
import org.qubership.cloud.dbaas.dto.role.PolicyRole;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.role.ServiceRole;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRolesDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2.MICROSERVICE_NAME;

@ApplicationScoped
@Slf4j
public class DatabaseRolesService {

    @Inject
    DatabaseRolesDbaasRepository databaseRolesDbaasRepository;

    public void saveRequestedRoles(String namespace, String serviceName, RolesRegistration rolesRegistrationRequest) {
        databaseRolesDbaasRepository.save(new DatabaseRole(rolesRegistrationRequest, serviceName, namespace, new Date()));
    }

    public List<DatabaseRole> copyDatabaseRole(String sourceNamespace, String targetNamespace) {
        log.info("Copy database roles from namespace {} to {}", sourceNamespace, targetNamespace);
        List<DatabaseRole> databaseRoles =
                databaseRolesDbaasRepository.findAllByNamespaceOrderedByTimeCreation(sourceNamespace).stream().map(role -> {
                    DatabaseRole databaseRole = new DatabaseRole(role);
                    databaseRole.setNamespace(targetNamespace);
                    return databaseRole;
                }).collect(Collectors.toList());

        return databaseRolesDbaasRepository.saveAll(databaseRoles);
    }

    public String getSupportedRoleFromRequest(UserRolesServices createRequest, String type, String namespace) {
        return getSupportRole(
                createRequest.getOriginService(),
                (String) createRequest.getClassifier().get(MICROSERVICE_NAME),
                namespace, type, createRequest.getUserRole()
        );

    }

    private String getSupportRole(String originService, String microserviceName, String namespace, String type, String requestedUserRole) {
        List<DatabaseRole> declarativeDatabaseRoles = databaseRolesDbaasRepository.findAllByMicroserviceNameAndNamespace(
                microserviceName, namespace);
        DatabaseRole databaseRole = declarativeDatabaseRoles == null ? null : declarativeDatabaseRoles.stream().filter(d -> d.getTimeRoleCreation() != null)
                .max(Comparator.comparing(DatabaseRole::getTimeRoleCreation)).orElse(null);
        String supportedRole;
        if (originService.equals(microserviceName)) {
            supportedRole = getUserRolesFromPolicy(databaseRole, type, requestedUserRole);
        } else {
            supportedRole = getUserRoleFromService(databaseRole, originService, requestedUserRole);
        }

        if (supportedRole == null) {
            if (databaseRole == null || !databaseRole.getDisableGlobalPermissions()) {
                requestedUserRole = requestedUserRole == null ? Role.ADMIN.toString() : requestedUserRole.toLowerCase();
                if (DatabaseRole.getGlobalPermissions().get(originService) == null)
                    return null;
                if (DatabaseRole.getGlobalPermissions().get(originService).contains(requestedUserRole)) {
                    supportedRole = requestedUserRole;
                }
            }
        }
        return supportedRole;
    }

    private String getUserRolesFromPolicy(DatabaseRole declarativeDatabaseRoles, String type, String userRole) {
        if (declarativeDatabaseRoles == null || declarativeDatabaseRoles.getPolicies() == null || declarativeDatabaseRoles.getPolicies().isEmpty()) {
            return userRole == null ? Role.ADMIN.toString() : userRole.toLowerCase();
        }
        Optional<PolicyRole> policyRole = declarativeDatabaseRoles.getPolicies().stream().
                filter(policy -> policy.getType().equals(type)).findFirst();
        if (policyRole.isEmpty()) {
            return userRole == null ? Role.ADMIN.toString() : userRole.toLowerCase();
        }
        if (userRole == null) {
            return policyRole.get().getDefaultRole();
        }
        if (policyRole.get().getAdditionalRole() == null || policyRole.get().getAdditionalRole().isEmpty()) {
            return userRole.toLowerCase();
        }
        return Stream.concat(policyRole.get().getAdditionalRole().stream(), Stream.of(policyRole.get().getDefaultRole()))
                .map(String::toLowerCase)
                .filter(userRole::equalsIgnoreCase)
                .findFirst()
                .orElse(null);
    }

    private String getUserRoleFromService(DatabaseRole existedDatabaseRoles, String originService, String userRole) {
        if (existedDatabaseRoles == null) {
            return null;
        }
        if (existedDatabaseRoles.getServices() != null) {
            Optional<ServiceRole> serviceRole = existedDatabaseRoles.getServices().stream().
                    filter(service -> service.getName().equals(originService)).findFirst();
            if (serviceRole.isEmpty()) {
                return null;
            }
            String finalUserRole = userRole == null ? Role.ADMIN.toString() : userRole;
            Optional<String> optionalRole = serviceRole.get().getRoles().stream().filter(v -> v.equalsIgnoreCase(finalUserRole)).findFirst();
            return optionalRole.map(String::toLowerCase).orElse(null);
        }
        return null;
    }

    public Optional<DatabaseRole> getAccessGrants(String namespace, String microserviceName) {
        List<DatabaseRole> declarativeDatabaseRoles = databaseRolesDbaasRepository.findAllByMicroserviceNameAndNamespace(
                microserviceName, namespace);
        Optional<DatabaseRole> databaseRoleOpt = declarativeDatabaseRoles == null ? Optional.empty() :
                declarativeDatabaseRoles.stream()
                        .filter(d -> d.getTimeRoleCreation() != null)
                        .max(Comparator.comparing(DatabaseRole::getTimeRoleCreation));
        return databaseRoleOpt;
    }

    public void removeDatabaseRole(String namespace) {
        databaseRolesDbaasRepository.deleteAllByNamespace(namespace);
    }
}
