package org.qubership.cloud.dbaas.dto.declarative;

import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.SortedMap;

@Data
@AllArgsConstructor
public class DatabaseToDeclarativeCreation implements Serializable {

    DatabaseDeclarativeConfig databaseDeclarativeConfig;
    Boolean cloneToNew;
    @Nullable
    SortedMap<String, Object> sourceClassifier;
}
