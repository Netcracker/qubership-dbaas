package org.qubership.cloud.dbaas.dto.v3;

import lombok.Data;

import java.util.List;

@Data
public class DebugLogicalDatabaseV3 {

    private String namespace;
    private String microservice;
    private String tenantId;
    private String logicalDbName;
    private String bgVersion;
    private String type;
    private List<String> roles;
    private String name;
    private String physicalDbId;
    private String physicalDbAdapterUrl;
    private DebugDatabaseDeclarativeConfigV3 declaration;
}
