package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseKind;
import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseType;
import lombok.Data;

import java.util.List;

@Data
public class FilterEntity {
    private List<String> namespace;
    private List<String> microserviceName;
    private List<DatabaseType> databaseType;
    private List<DatabaseKind> databaseKind;
}
