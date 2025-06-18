package org.qubership.cloud.dbaas.dto.v3;

import lombok.Data;

import java.util.SortedMap;
import java.util.UUID;

@Data
public class DebugDatabaseDeclarativeConfigV3 {

    private UUID id;
    private SortedMap<String, Object> settings;
    private Boolean lazy;
    private String instantiationApproach;
    private String versioningApproach;
    private String versioningType;
    private SortedMap<String, Object> classifier;
    private String type;
    private String namePrefix;
    private String namespace;
}
