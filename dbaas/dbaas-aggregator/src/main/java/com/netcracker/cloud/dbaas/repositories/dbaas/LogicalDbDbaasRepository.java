package com.netcracker.cloud.dbaas.repositories.dbaas;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public interface LogicalDbDbaasRepository {
    DatabaseRegistryDbaasRepository getDatabaseRegistryDbaasRepository();

    DatabaseDbaasRepository getDatabaseDbaasRepository();
}
