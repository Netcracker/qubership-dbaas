package org.qubership.cloud.dbaas.dto.migration;

import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils.toStringWithMaskedPassword;

@Schema(description = "Request to add database to registration")
@Data
@NoArgsConstructor(force = true)
@EqualsAndHashCode
public class RegisterDatabaseRequest {

    @Schema(required = true, description = "Classifier describes the purpose of database, it should not exist in registration, or the request would be ignored.")
    @NonNull
    private SortedMap<String, Object> classifier;

    @Schema(required = true, description = "Connection properties used to connect to database. It contains such keys as url, authDbName, username, password, port, host." +
            " Setting keys depends on the database type.")
    @NonNull
    private Map<String, Object> connectionProperties;

    @Schema(required = true, description = "List of the resources needed to drop during database drop.")
    @NonNull
    private List<DbResource> resources;

    @Schema(required = true, description = "Namespace where database is placed.")
    @NonNull
    private String namespace;

    @Schema(required = true, description = "The type of database, for example postgresql or mongodb.")
    @NonNull
    private String type;

    @Schema(required = false, description = "Identifier of an adapter to work with database. If not specified then the default would be used.")
    private String adapterId;

    @Schema(required = true, description = "Name of database.")
    @NonNull
    private String name;

    @Schema(description = "This parameter specifies if the DbaaS should except this database from backup/restore procedure. " +
            "The parameter cannot be modified and it is installed only once during registration request.")
    private Boolean backupDisabled = false;

    @Schema(required = false, description = "Physical database identifier where the registered database " +
            "should be located. If it is absent, adapter id may be used to identify the target physical database.")
    private String physicalDatabaseId;

    /**
     * Returns a string representation of the request. Can be safely used for logging.
     *
     * @return string representation of the request with masked passwords
     */
    @Override
    public String toString() {
        return "RegisterDatabaseRequest{" +
                "classifier=" + classifier +
                ", connectionProperties=" + toStringWithMaskedPassword(List.of(connectionProperties)) +
                ", resources=" + resources +
                ", namespace='" + namespace + '\'' +
                ", type='" + type + '\'' +
                ", adapterId='" + adapterId + '\'' +
                ", name='" + name + '\'' +
                ", backupDisabled=" + backupDisabled +
                ", physicalDatabaseId='" + physicalDatabaseId + '\'' +
                '}';
    }
}
