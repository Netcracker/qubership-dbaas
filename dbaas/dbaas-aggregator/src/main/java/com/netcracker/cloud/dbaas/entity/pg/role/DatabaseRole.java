package org.qubership.cloud.dbaas.entity.pg.role;

import org.qubership.cloud.dbaas.converter.ListPolicyRole;
import org.qubership.cloud.dbaas.converter.ListServiceRole;
import org.qubership.cloud.dbaas.dto.conigs.RolesRegistration;
import org.qubership.cloud.dbaas.dto.role.PolicyRole;
import org.qubership.cloud.dbaas.dto.role.ServiceRole;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.hibernate.Hibernate;

import java.util.*;
import java.util.stream.Collectors;

@Data
@ToString
@RequiredArgsConstructor
@Entity(name = "Database_role")
@Table(name = "database_role")
public class DatabaseRole {

    @Id
    @GeneratedValue
    private UUID id;

    @Schema(description = "Lists services roles")
    @Convert(converter = ListServiceRole.class)
    @Column(name = "services")
    private List<ServiceRole> services;

    @Schema(description = "Lists polices roles")
    @Convert(converter = ListPolicyRole.class)
    @Column(name = "policies")
    private List<PolicyRole> policies;

    @Column(name = "namespace")
    private String namespace;

    @Column(name = "microservice_name")
    private String microserviceName;

    @Schema(description = "Time role creation")
    @Column(name = "time_role_creation")
    private Date timeRoleCreation;

    @Column(name = "disable_global_permissions")
    @Schema(description = "Is global permissions disabled")
    private Boolean disableGlobalPermissions;

    @Getter
    private static Map<String, List<String>> globalPermissions = getGlobalPermissionsList();


    public DatabaseRole(DatabaseRole databaseRole) {
        this.services = databaseRole.getServices() == null ? null : databaseRole.getServices().stream().map(ServiceRole::new).collect(Collectors.toList());
        this.policies = databaseRole.getPolicies() == null ? null : databaseRole.getPolicies().stream().map(PolicyRole::new).collect(Collectors.toList());
        this.namespace = databaseRole.getNamespace();
        this.microserviceName = databaseRole.getMicroserviceName();
        this.timeRoleCreation = new Date();
    }

    public DatabaseRole(RolesRegistration rolesRegistrationRequest, String microserviceName, String namespace, Date date) {
        this.services = rolesRegistrationRequest.getServices();
        this.policies = rolesRegistrationRequest.getPolicy();
        this.namespace = namespace;
        this.microserviceName = microserviceName;
        this.timeRoleCreation = date;
        this.disableGlobalPermissions = rolesRegistrationRequest.getDisableGlobalPermissions() != null && rolesRegistrationRequest.getDisableGlobalPermissions();
    }

    private static Map<String, List<String>> getGlobalPermissionsList() {
        Map<String, List<String>> defaultRoles = new HashMap<>();
        defaultRoles.put("cdc-streaming-platform", List.of("streaming"));
        defaultRoles.put("cdc-control", List.of("streaming"));
        defaultRoles.put("data-slicing-tool", List.of("admin"));
        defaultRoles.put("df-tool-backend", List.of("rw"));
        defaultRoles.put("dpc-backend", List.of("ro"));
        return defaultRoles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        DatabaseRole that = (DatabaseRole) o;
        return Objects.equals(id, that.id) && Objects.equals(services, that.services) && Objects.equals(policies, that.policies) && Objects.equals(namespace, that.namespace) && Objects.equals(microserviceName, that.microserviceName);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
