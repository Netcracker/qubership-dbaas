package com.netcracker.cloud.dbaas.dto.v3;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

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
