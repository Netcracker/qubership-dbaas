package org.qubership.cloud.dbaas.dto.v3;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;

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
public class DatabaseResponseV3ListCP extends DatabaseResponseV3 {

    @Schema(required = true, description = "This is an information about connection to database. It contains such keys as url, authDbName, username, password, port, host." +
            "Setting keys depends on the database type.")
    private List<Map<String, Object>> connectionProperties;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "list of resources is necessary for bulk drop resources operation. Specified if you add query parameter \"withResources\" = true to request", ref = "DbResource")
    private List<DbResource> resources;

    public DatabaseResponseV3ListCP(DatabaseRegistry databaseRegistry, String physicalDatabaseId) {
        super(databaseRegistry, physicalDatabaseId);
        connectionProperties = databaseRegistry.getDatabase().getConnectionProperties();

    }
}