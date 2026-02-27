package com.netcracker.cloud.dbaas.dto.v3;

import com.netcracker.cloud.dbaas.entity.pg.BgDomain;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.Backup;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.Restore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DumpResponseV3 {
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private DumpRulesV3 rules;
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private List<Database> logicalDatabases;
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private List<PhysicalDatabase> physicalDatabases;
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private List<DatabaseDeclarativeConfig> declarativeConfigurations;
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private List<BgDomain> blueGreenDomains;
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private List<Backup> backups;
    @Schema(implementation = Object.class, type = SchemaType.ARRAY)
    private List<Restore> restores;
}
