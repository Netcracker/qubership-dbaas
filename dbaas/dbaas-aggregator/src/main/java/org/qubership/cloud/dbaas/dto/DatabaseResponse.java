package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.exceptions.EmptyConnectionPropertiesException;
import org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.V3_TRANSFORMATION;

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
public class DatabaseResponse {
    @Schema(description = "A unique identifier of the document in the database. This field might not be used when searching by classifier for security purpose. " +
            "And it exists in the response when executing Create database API")
    private UUID id;

    @Schema(required = true, description = "Classifier describes the purpose of the database and it distinguishes this database from other databases in the same namespace. " +
            "It contains such keys as dbClassifier, isServiceDb, microserviceName, namespace. Setting keys depends on the database type.")
    private SortedMap<String, Object> classifier;

    @Schema(required = true, description = "This is an information about connection to database. It contains such keys as url, authDbName, username, password, port, host." +
            "Setting keys depends on the database type.")
    private Map<String, Object> connectionProperties;

    @Schema(required = true, description = "It lists the resources which will be deleted when sending the request to delete the database.",
            ref = "DbResource")
    private List<DbResource> resources;

    @Schema(required = true, description = "Namespace where database is placed.")
    private String namespace;

    @Schema(required = true, description = "Type of database, for example postgresql or mongodb")
    private String type;

    @Schema(required = true, description = "This field indicates for which adapter the database was created.")
    private String adapterId;

    @Schema(required = true, description = "Name of database. It may be generated or, if name was specified in the request, then it will be specified.")
    private String name;

    @Schema(description = "A marker indicating if the database will be deleted.")
    private boolean markedForDrop;

    @Schema(description = "Time to create a database.")
    private Date timeDbCreation;

    private Boolean backupDisabled;

    @Schema(description = "Additional settings for creating a database.")
    private Map<String, Object> settings;

    @Schema(description = "This parameter describes a connection properties.")
    private ConnectionDescription connectionDescription;

    @Schema(description = "List of warning messages.")
    private List<String> warnings;

    @Schema(description = "This parameter specifies if a control over the database is not carried out by the DbaaS adapter")
    private boolean externallyManageable = false;

    @Schema(description = "The list of roles which are related to this logical database. The external security service (e.g. DBaaA Agent) can perform a verification process on this field.")
    private List<String> dbOwnerRoles;

    @Schema(description = "Indicate that classifier migrated correctly to V3 structure")
    private boolean classifierV3Migrated = true;

    /**
     * Creates deep copy of the {@code database} object, so modifications to the request won't affect database itself
     *
     * @param databaseRegistry the database object to create response from
     */
    public DatabaseResponse(DatabaseRegistry databaseRegistry) {
        id = databaseRegistry.getId();
        classifier = Optional.ofNullable(databaseRegistry.getDatabase().getOldClassifier()).map(TreeMap::new).orElse(null);
        connectionProperties = Optional.of(ConnectionPropertiesUtils.getConnectionProperties(databaseRegistry.getDatabase().getConnectionProperties(), Role.ADMIN.toString()))
                .map(HashMap::new).orElseThrow(EmptyConnectionPropertiesException::new);
        if (databaseRegistry.getClassifier() == null) {
            classifierV3Migrated = false;
        } else {
            classifierV3Migrated = databaseRegistry.getClassifier().get(V3_TRANSFORMATION) == null || !databaseRegistry.getClassifier().get(V3_TRANSFORMATION).equals("fail");
        }
        resources = Optional.ofNullable(databaseRegistry.getDatabase().getResources()).map(ArrayList::new).orElse(null);
        namespace = databaseRegistry.getNamespace();
        type = databaseRegistry.getType();
        adapterId = databaseRegistry.getDatabase().getAdapterId();
        name = databaseRegistry.getDatabase().getName();
        markedForDrop = databaseRegistry.getDatabase().isMarkedForDrop();
        timeDbCreation = Optional.ofNullable(databaseRegistry.getTimeDbCreation()).map(date -> (Date) date.clone()).orElse(null);
        backupDisabled = databaseRegistry.getDatabase().getBackupDisabled();
        settings = Optional.ofNullable(databaseRegistry.getDatabase().getSettings()).map(HashMap::new).orElse(null);
        connectionDescription = Optional.ofNullable(databaseRegistry.getDatabase().getConnectionDescription()).map(conDesc -> new ConnectionDescription(conDesc.fields)).orElse(null);
        warnings = Optional.ofNullable(databaseRegistry.getDatabase().getWarnings()).map(ArrayList::new).orElse(null);
        externallyManageable = databaseRegistry.getDatabase().isExternallyManageable();
    }
}
