package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.role.DatabaseRole;

import java.util.List;

public interface DatabaseRolesDbaasRepository {

    List<DatabaseRole> findAllByMicroserviceNameAndNamespace(String microserviceName, String namespace);

    List<DatabaseRole> findAllByNamespaceOrderedByTimeCreation(String namespace);

    void save(DatabaseRole databaseRole);

    List<DatabaseRole> saveAll(List<DatabaseRole> databaseRoles);

    void deleteAllByNamespace(String namespace);
}
