package com.netcracker.cloud.dbaas.dto.bluegreen;

import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Data
public class NewDatabaseProcessObject extends AbstractDatabaseProcessObject implements Serializable {
    public NewDatabaseProcessObject(DatabaseDeclarativeConfig config, String version) {
        super(UUID.randomUUID(), config, version);
    }
}
