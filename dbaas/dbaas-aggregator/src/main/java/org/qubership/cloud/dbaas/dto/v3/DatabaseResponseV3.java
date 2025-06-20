package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * <p>A class that is used as database response model. It contains only those fields,
 * that can be safely returned to a client.
 * </p>
 * <p>All the HTTP endpoints must return object of this class instead of {@link Database},
 * because instance of the {@link Database} class in future will possibly contain some internal information.
 * </p>
 */
@Data
@NoArgsConstructor
public abstract class DatabaseResponseV3 {
    @Schema(description = "A unique identifier of the document in the database. This field might not be used when searching by classifier for security purpose. " +
            "And it exists in the response when executing Create database API")
    private UUID id;

    @Schema(required = true, description = "Classifier describes the purpose of the database and it distinguishes this database from other databases in the same namespace. " +
            "It contains such keys as dbClassifier, isServiceDb, microserviceName, namespace. Setting keys depends on the database type.")
    private SortedMap<String, Object> classifier;

    @Schema(required = true, description = "Namespace where database is placed.")
    private String namespace;

    @Schema(required = true, description = "Type of database, for example PostgreSQL or MongoDB")
    private String type;

    @Schema(required = true, description = "Name of database. It may be generated or, if name was specified in the request, then it will be specified.")
    private String name;

    @Schema(description = "This parameter specifies if a control over the database is not carried out by the DbaaS adapter")
    private boolean externallyManageable = false;

    @Schema(description = "Time to create a database.")
    private Date timeDbCreation;

    @Schema(description = "Additional settings for creating a database.")
    private Map<String, Object> settings;

    @Schema(description = "This field indicates if backup is disabled or not. If true, database would not be backed up. Example: false")
    private Boolean backupDisabled;

    @Schema(description = "Physical database identifier where the registered database " +
            "should be located. If it is absent, adapter id may be used to identify the target physical database.")
    private String physicalDatabaseId;

    /**
     * Creates deep copy of the {@code databaseRegistry} object, so modifications to the request won't affect database itself
     *
     * @param databaseRegistry the database object to create response from
     */
    public DatabaseResponseV3(DatabaseRegistry databaseRegistry, String physicalDatabaseId) {
        id = databaseRegistry.getDatabase().getId();
        classifier = Optional.ofNullable(databaseRegistry.getClassifier()).map(TreeMap::new).orElse(null);
        namespace = databaseRegistry.getNamespace();
        type = databaseRegistry.getType();
        name = databaseRegistry.getName();
        externallyManageable = databaseRegistry.isExternallyManageable();
        timeDbCreation = Optional.ofNullable(databaseRegistry.getDatabase().getTimeDbCreation()).map(date -> (Date) date.clone()).orElse(null);
        settings = Optional.ofNullable(databaseRegistry.getSettings()).map(HashMap::new).orElse(null);
        backupDisabled = databaseRegistry.getBackupDisabled();
        this.physicalDatabaseId = physicalDatabaseId;
    }

}