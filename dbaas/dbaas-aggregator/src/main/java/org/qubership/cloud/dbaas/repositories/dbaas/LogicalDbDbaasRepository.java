package org.qubership.cloud.dbaas.repositories.dbaas;

public interface LogicalDbDbaasRepository {
    DatabaseRegistryDbaasRepository getDatabaseRegistryDbaasRepository();

    DatabaseDbaasRepository getDatabaseDbaasRepository();
}
