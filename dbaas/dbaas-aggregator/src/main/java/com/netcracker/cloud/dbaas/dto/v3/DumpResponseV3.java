package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.entity.pg.BgDomain;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
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
