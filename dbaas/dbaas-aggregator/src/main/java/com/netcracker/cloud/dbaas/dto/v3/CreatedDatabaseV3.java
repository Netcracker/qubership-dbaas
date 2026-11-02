package com.netcracker.cloud.dbaas.dto.v3;

import com.netcracker.cloud.dbaas.dto.ConnectionDescription;
import com.netcracker.cloud.dbaas.dto.CreatedDatabase;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.dto.role.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.service.ConnectionPropertiesUtils.toStringWithMaskedPassword;

@Data
@NoArgsConstructor
@Schema(description = "Returns properties of created database")
public class CreatedDatabaseV3 {
    @Schema(description = "connection properties for connect to database")
    private List<Map<String, Object>> connectionProperties;
    @Schema(description = "List of resources is necessary for bulk drop resources operation")
    private List<DbResource> resources;
    @Schema(description = "name of the created database")
    private String name;
    @Schema(description = "Identifier of the adapter which created the database")
    private String adapterId;
    @Schema(description = "Description of the connection properties fields, for example which of them are protected")
    private ConnectionDescription connectionDescription;

    public CreatedDatabaseV3(CreatedDatabase database) {
        if (!database.getConnectionProperties().containsKey(ROLE)) {
            database.getConnectionProperties().put(ROLE, Role.ADMIN.toString());
        }
        this.connectionProperties = List.of(database.getConnectionProperties());
        this.resources = database.getResources();
        this.name = database.getName();
        this.adapterId = database.getAdapterId();
        this.connectionDescription = database.getConnectionDescription();
    }

    @Override
    public String toString() {
        return "CreatedDatabaseV3{" +
                "connectionProperties=" + toStringWithMaskedPassword(connectionProperties) +
                ", resources=" + resources +
                ", name='" + name + '\'' +
                ", adapterId='" + adapterId + '\'' +
                ", connectionDescription=" + connectionDescription +
                '}';
    }
}
