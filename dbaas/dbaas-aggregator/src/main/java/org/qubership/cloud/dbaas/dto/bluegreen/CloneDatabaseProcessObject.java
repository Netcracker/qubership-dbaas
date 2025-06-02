package org.qubership.cloud.dbaas.dto.bluegreen;

import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.SortedMap;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloneDatabaseProcessObject extends AbstractDatabaseProcessObject implements Serializable {
    private SortedMap<String, Object> sourceClassifier;
    private String sourceNamespace;
    private UUID backupId;
    private UUID restoreId;

    public CloneDatabaseProcessObject(DatabaseDeclarativeConfig config, String version, SortedMap<String, Object> sourceClassifier,
                                      String sourceNamespace) {
        super(UUID.randomUUID(), config, version);
        this.sourceClassifier = sourceClassifier;
        this.backupId = UUID.randomUUID();
        this.restoreId = UUID.randomUUID();
        this.sourceNamespace = sourceNamespace;
    }
}
