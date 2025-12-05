package com.netcracker.cloud.dbaas.entity.pg.backup;

import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseKind;
import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseType;

import java.util.Set;

public record FilterData(
        Set<String> namespaces,
        Set<String> microserviceNames,
        Set<DatabaseType> databaseTypes,
        Set<DatabaseKind> databaseKinds) {}
