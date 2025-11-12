package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseKind;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FilterEntity {
    private List<String> namespace;
    private List<String> microserviceName;
    private List<DatabaseType> databaseType;
    private List<DatabaseKind> databaseKind;
}
