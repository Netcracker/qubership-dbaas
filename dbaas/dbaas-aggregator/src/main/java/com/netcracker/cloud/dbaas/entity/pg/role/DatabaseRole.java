package com.netcracker.cloud.dbaas.entity.pg.role;

import com.netcracker.cloud.dbaas.converter.ListPolicyRole;
import com.netcracker.cloud.dbaas.converter.ListServiceRole;
import com.netcracker.cloud.dbaas.dto.conigs.RolesRegistration;
import com.netcracker.cloud.dbaas.dto.role.PolicyRole;
import com.netcracker.cloud.dbaas.dto.role.ServiceRole;
import jakarta.persistence.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.hibernate.Hibernate;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
