package org.qubership.cloud.dbaas.dto.v3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DumpDefaultRuleV3 {

    private String physicalDatabaseId;
    private String address;
}
