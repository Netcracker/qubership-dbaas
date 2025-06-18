package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@NoArgsConstructor
public class ExternalDatabaseResponseV3 {
    @Schema(description = "A unique identifier of the document in the database. This field may be not when used search by classifier for security purpose. " +
            "And exist in response when execute create database api")
    private UUID id;
    @Schema(required = true, description = "Classifier describes purpose of database and separated this database from other database in the same namespase. " +
            "It contains such as key dbClassifier, isServiceDb, microserviceName, namespace. Set keys depends on the database type")
    private SortedMap<String, Object> classifier;
    @Schema(required = true, description = "There are information about connection to database. It contains such as key url, authDbName, username, password, port, host." +
            "Set keys depends on the database type")
    private List<Map<String, Object>> connectionProperties;
    @Schema(required = true, description = "Namespace where database placed")
    private String namespace;
    @Schema(required = true, description = "Type of physical database which logical base belongs to")
    private String type;
    @Schema(required = true, description = "Name of logical database.")
    private String name;
    @Schema(required = true, description = "Database creation time")
    private Date timeDbCreation;
    @Schema(description = "This parameter specifies that control over the database is not carried out by the DbaaS adapter")
    private boolean externallyManageable;

    public ExternalDatabaseResponseV3(DatabaseResponseV3ListCP databaseResponse) {
        this.id = databaseResponse.getId();
        this.classifier = databaseResponse.getClassifier();
        this.connectionProperties = databaseResponse.getConnectionProperties();
        this.namespace = databaseResponse.getNamespace();
        this.externallyManageable = databaseResponse.isExternallyManageable();
        this.type = databaseResponse.getType();
        this.name = databaseResponse.getName();
        this.timeDbCreation = databaseResponse.getTimeDbCreation();
    }
}
