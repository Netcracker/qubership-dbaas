package org.qubership.cloud.dbaas.entity.shared;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.UUID;

@Data
@EqualsAndHashCode
@MappedSuperclass
public abstract class AbstractDbState implements Serializable {

    @Id
    protected UUID id;

    @Deprecated
    protected DatabaseStateStatus state;

    protected AbstractDbState() {
        this.id = UUID.randomUUID();
    }

    protected AbstractDbState(UUID id, DatabaseStateStatus state, DatabaseStateStatus databaseState, String description, String podName) {
        this.id = id;
        this.state = state;
        this.databaseState = databaseState;
        this.description = description;
        this.podName = podName;
    }

    public void setDatabaseState(DatabaseStateStatus databaseState) {
        this.databaseState = databaseState;
        this.state = databaseState;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "database_state")
    protected DatabaseStateStatus databaseState;

    protected String description;

    @Column(name = "pod_name")
    protected String podName;

    public AbstractDbState(DatabaseStateStatus state) {
        this.id = UUID.randomUUID();
        this.state = state;
        this.databaseState = state;
    }

    public AbstractDbState(DatabaseStateStatus state, String podName) {
        this.id = UUID.randomUUID();
        this.state = state;
        this.databaseState = state;
        this.podName = podName;
    }

    public AbstractDbState(AbstractDbState dbState) {
        this.id = UUID.randomUUID();
        this.state = dbState.getState();
        this.databaseState = dbState.getDatabaseState();
        this.description = dbState.description;
        this.podName = dbState.podName;
    }

    public enum DatabaseStateStatus {
        PROCESSING,
        CREATED,
        DELETING,
        DELETING_FAILED,
        ARCHIVED,
        ORPHAN
    }
}
