package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRolesDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRoleRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;

import java.util.List;

@AllArgsConstructor
@ApplicationScoped
@Transactional
public class DatabaseRolesDbaasRepositoryImpl implements DatabaseRolesDbaasRepository {

    private DatabaseRoleRepository databasesRoleRepository;

    @Override
    public List<DatabaseRole> findAllByMicroserviceNameAndNamespace(String microserviceName, String namespace) {
        return databasesRoleRepository.findAllByMicroserviceNameAndNamespace(microserviceName, namespace);
    }

    public List<DatabaseRole> findAllByNamespaceOrderedByTimeCreation(String namespace) {
        return databasesRoleRepository.findAllByNamespaceOrderedByTimeCreation(namespace);
    }

    @Override
    public void save(DatabaseRole databaseRole) {
        databasesRoleRepository.persist(databaseRole);
    }

    @Override
    public List<DatabaseRole> saveAll(List<DatabaseRole> databaseRoles) {
        databasesRoleRepository.persist(databaseRoles);
        return databaseRoles;
    }

    public void deleteAllByNamespace(String namespace) {
        databasesRoleRepository.deleteAllByNamespace(namespace);
    }
}
