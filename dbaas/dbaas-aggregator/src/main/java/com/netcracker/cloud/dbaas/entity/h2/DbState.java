package org.qubership.cloud.dbaas.entity.h2;

import org.qubership.cloud.dbaas.entity.shared.AbstractDbState;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Entity(name = "DbState")
@Table(name = "database_state_info")
public class DbState extends AbstractDbState {

    public org.qubership.cloud.dbaas.entity.pg.DbState asPgEntity() {
        org.qubership.cloud.dbaas.entity.pg.DbState clone = new org.qubership.cloud.dbaas.entity.pg.DbState();
        clone.setId(this.id);
        clone.setState(this.state);
        clone.setDatabaseState(this.databaseState);
        clone.setDescription(this.description);
        clone.setPodName(this.podName);
        return clone;
    }
}
