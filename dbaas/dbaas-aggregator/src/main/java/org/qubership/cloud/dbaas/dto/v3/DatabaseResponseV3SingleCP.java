package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;

import static org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils.toStringWithMaskedPassword;

/**
 * <p>A class that is used as database response model. It contains only those fields,
 * that can be safely returned to a client.
 * </p>
 * <p>All the HTTP endpoints must return object of this class instead of {@link Database},
 * because instance of the {@link Database} class in future will possibly contain some internal information.
 * </p>
 */
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
@NoArgsConstructor
public class DatabaseResponseV3SingleCP extends DatabaseResponseV3 {

    @Schema(required = true, description = "This is an information about connection to database. It contains such keys as url, authDbName, username, password, port, host." +
            "Setting keys depends on the database type.")
    private Map<String, Object> connectionProperties;

    /**
     * Creates deep copy of the {@code database} object, so modifications to the request won't affect database itself
     *
     * @param databaseRegistry the database object to create response from
     */

    public DatabaseResponseV3SingleCP(DatabaseRegistry databaseRegistry, String physicalDatabaseId, String role) {
        super(databaseRegistry, physicalDatabaseId);
        connectionProperties = ConnectionPropertiesUtils.getConnectionProperties(databaseRegistry.getConnectionProperties(), role);
    }

    @Override
    public String toString() {
        return "DatabaseResponseV3SingleCP{" +
                "super=" + super.toString() +
                ", connectionProperties=" + toStringWithMaskedPassword(List.of(connectionProperties)) +
                '}';
    }
}