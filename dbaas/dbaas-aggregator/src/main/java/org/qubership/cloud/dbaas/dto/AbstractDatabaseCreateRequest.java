package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.Map;

@Data
@NoArgsConstructor(force = true)
public abstract class AbstractDatabaseCreateRequest {

    protected AbstractDatabaseCreateRequest(@NonNull Map<String, Object> classifier, @NonNull String type) {
        this.classifier = classifier;
        this.type = type;
    }

    @Schema(required = true, description = "Classifier describes the purpose of database and distinguishes this database from other databases in the same namespace. " +
            "It contains such keys as dbClassifier, isServiceDb, microserviceName, namespace. Setting keys depends on the database type. If database with such classifier " +
            "exists, then this database will be given away. The backupDisabled parameter can not be modified; it is installed only once during creating database request.")
    @NonNull
    private Map<String, Object> classifier;

    @Schema(required = true, description = "Describes the type of database in which you want to create a database. For example mongodb or postgresql")
    @NonNull
    private String type;

    @Schema(description = "This is a prefix of the database name. Prefix depends on the type of the database and it should be less than 27 characters if dbName is not specified.")
    private String namePrefix;

    @Schema(description = "This field indicates if backup is disabled or not. If true - database would not be backed up.")
    private Boolean backupDisabled = false;

    @Schema(description = "Additional settings for creating database. There is a possibility to update settings after database creation.")
    private Map<String, Object> settings;

    @Schema(description = "Specifies the identificator of physical database where a logical database will be created. If it is not specified then logical database will be created in default physical database. " +
            "You can get the list of all physical databases by \"List registered physical databases\" API.")
    private String physicalDatabaseId;
}
