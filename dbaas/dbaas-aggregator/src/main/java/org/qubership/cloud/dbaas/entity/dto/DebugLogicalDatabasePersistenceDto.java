package org.qubership.cloud.dbaas.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class DebugLogicalDatabasePersistenceDto {

    private String logicalDatabaseClassifier;
    private String logicalDatabaseBgVersion;
    private String logicalDatabaseType;
    private String logicalDatabaseConnectionProperties;
    private String logicalDatabaseName;
    private String logicalDatabasePhysicalDatabaseId;
    private String externalAdapterRegistrationAddress;
    private UUID databaseDeclarativeConfigId;
    private String databaseDeclarativeConfigSettings;
    private Boolean databaseDeclarativeConfigLazy;
    private String databaseDeclarativeConfigInstantiationApproach;
    private String databaseDeclarativeConfigVersioningApproach;
    private String databaseDeclarativeConfigVersioningType;
    private String databaseDeclarativeConfigClassifier;
    private String databaseDeclarativeConfigType;
    private String databaseDeclarativeConfigNamePrefix;
    private String databaseDeclarativeConfigNamespace;
}
