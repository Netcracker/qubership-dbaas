package org.qubership.cloud.dbaas.dto.bluegreen;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrphanDatabasesResponse {
    String dbName;
    Map<String, Object> classifier;
    String type;
    String namespace;
    Date dbCreationTime;
    String physicalDbId;
    String bgVersion;

    public OrphanDatabasesResponse(DatabaseRegistry databaseRegistry) {
        this.dbName = databaseRegistry.getName();
        this.classifier = databaseRegistry.getClassifier();
        this.type = databaseRegistry.getType();
        this.namespace = databaseRegistry.getNamespace();
        this.dbCreationTime = databaseRegistry.getTimeDbCreation();
        this.physicalDbId = databaseRegistry.getPhysicalDatabaseId();
        this.bgVersion = databaseRegistry.getBgVersion();
    }
}
