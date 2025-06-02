package org.qubership.cloud.dbaas.dto.bluegreen;

import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class AbstractDatabaseProcessObject implements Serializable {
    private UUID id;
    private DatabaseDeclarativeConfig config;
    private String version;
    @Nullable
    private String physicalDatabaseId;

    public AbstractDatabaseProcessObject(UUID id, DatabaseDeclarativeConfig config, String version) {
        this.id = id;
        this.config = config;
        this.version = version;
    }
}
