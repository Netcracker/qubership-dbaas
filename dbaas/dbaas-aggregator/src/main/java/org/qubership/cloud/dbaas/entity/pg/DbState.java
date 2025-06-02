package org.qubership.cloud.dbaas.entity.pg;

import org.qubership.cloud.dbaas.entity.shared.AbstractDbState;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

import java.util.UUID;

@NoArgsConstructor
@Entity(name = "DbState")
@Table(name = "database_state_info")
public class DbState extends AbstractDbState {

    public DbState(UUID id, DatabaseStateStatus state, DatabaseStateStatus databaseState, String description, String podName) {
        super(id, state, databaseState, description, podName);
    }

    public DbState(DatabaseStateStatus state) {
        super(state);
    }

    public DbState(DatabaseStateStatus state, String podName) {
        super(state, podName);
    }

    public DbState(AbstractDbState dbState) {
        super(dbState);
    }

    public org.qubership.cloud.dbaas.entity.h2.DbState asH2Entity() {
        org.qubership.cloud.dbaas.entity.h2.DbState clone = new org.qubership.cloud.dbaas.entity.h2.DbState();
        clone.setId(this.id);
        clone.setState(this.state);
        clone.setDatabaseState(this.databaseState);
        clone.setDescription(this.description);
        clone.setPodName(this.podName);
        return clone;
    }
}
