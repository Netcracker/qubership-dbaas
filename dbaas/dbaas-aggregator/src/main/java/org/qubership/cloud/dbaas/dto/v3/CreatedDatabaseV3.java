package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.dto.ConnectionDescription;
import org.qubership.cloud.dbaas.dto.CreatedDatabase;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.dto.role.Role;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils.toStringWithMaskedPassword;

@Data
@NoArgsConstructor
public class CreatedDatabaseV3 {
    private List<Map<String, Object>> connectionProperties;
    private List<DbResource> resources;
    private String name;
    private String adapterId;
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
