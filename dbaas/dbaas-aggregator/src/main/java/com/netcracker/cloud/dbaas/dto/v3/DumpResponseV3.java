package com.netcracker.cloud.dbaas.dto.v3;

import com.netcracker.cloud.dbaas.entity.pg.BgDomain;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DumpResponseV3 {

    private DumpRulesV3 rules;
    private List<Database> logicalDatabases;
    private List<PhysicalDatabase> physicalDatabases;
    private List<DatabaseDeclarativeConfig> declarativeConfigurations;
    private List<BgDomain> blueGreenDomains;
}
