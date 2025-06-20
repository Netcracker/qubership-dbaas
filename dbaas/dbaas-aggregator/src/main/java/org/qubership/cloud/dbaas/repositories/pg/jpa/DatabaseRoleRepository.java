package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DatabaseRoleRepository implements PanacheRepositoryBase<DatabaseRole, UUID> {

    public List<DatabaseRole> findAllByMicroserviceNameAndNamespace(String microserviceName, String namespace) {
        return list("microserviceName = ?1 and namespace = ?2", microserviceName, namespace);
    }

    public void deleteAllByNamespace(String namespace) {
        delete("namespace", namespace);
    }

    public List<DatabaseRole> findAllByNamespaceOrderedByTimeCreation(String namespace) {
        return getEntityManager().createNativeQuery("select distinct on (r.microservice_name) * from database_role r where r.namespace = ?1" +
                " order by r.microservice_name, r.time_role_creation desc", DatabaseRole.class).setParameter(1, namespace).getResultList();
    }
}
