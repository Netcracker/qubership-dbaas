package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.ConnectionPropertiesNotContainRoleException;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NonNull;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.NAMESPACE;
import static org.qubership.cloud.dbaas.Constants.ROLE;


@Data
public class ExternalDatabaseRequestV3 {
    @NonNull
    @Schema(required = true, description = "See the description of \"classifier\" properties of DatabaseCreateRequest entity")
    private SortedMap<String, Object> classifier;
    @NonNull
    @Schema(required = true, description = "There is an information about connection to database. It contains such keys as url, authDbName, port, host. username and password do not need to specify here." +
            "You should specify them in order for the client to be able to connect to the database.")
    private List<Map<String, Object>> connectionProperties;
    @NonNull
    @Schema(required = true, description = "Type of physical database.")
    private String type;
    @NonNull
    @Schema(required = true, description = "Name of logical database.")
    private String dbName;
    @Schema(required = false, description = "Is connection properties update required. False by default. If true, then old connection properties will be replaced by the new ones provided.")
    private Boolean updateConnectionProperties = false;

    public DatabaseRegistry toDatabaseRegistry() {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setClassifier(this.getClassifier());
        if (this.connectionProperties.stream().anyMatch(cp -> !cp.containsKey(ROLE))) {
            throw new ConnectionPropertiesNotContainRoleException(this.getClassifier());
        }
        database.setName(this.getDbName());
        database.setConnectionProperties(this.connectionProperties);
        database.setType(this.getType());
        database.setExternallyManageable(true);
        database.setBackupDisabled(true);
        database.setNamespace((String) this.getClassifier().get(NAMESPACE));
        database.setTimeDbCreation(new Date());

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType(this.getType());
        databaseRegistry.setNamespace((String) this.getClassifier().get(NAMESPACE));
        databaseRegistry.setClassifier(this.getClassifier());
        databaseRegistry.setDatabase(database);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        return databaseRegistry;
    }
}
