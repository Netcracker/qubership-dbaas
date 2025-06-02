package org.qubership.cloud.dbaas.entity.h2;

import org.qubership.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Delegate;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

@Data
@Entity(name = "DatabaseRegistry")
@Table(name = "classifier")
@AllArgsConstructor
public class DatabaseRegistry extends AbstractDatabaseRegistry {

    public DatabaseRegistry() {
        this.id = UUID.randomUUID();
    }

    @Schema(required = true, description = "It lists of database classifiers",
            ref = "Database")
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "database_id")
    @Delegate(excludes = {IgnoredDelegates.class, AbstractDatabaseRegistry.class})
    protected Database database;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseRegistry that = (DatabaseRegistry) o;
        return Objects.equals(getId(), that.getId()) &&
                Objects.equals(getDatabase().getId(), that.getDatabase().getId()) &&
                Objects.equals(getTimeDbCreation(), that.getTimeDbCreation()) &&
                Objects.equals(getClassifier(), that.getClassifier()) &&
                Objects.equals(getNamespace(), that.getNamespace()) &&
                Objects.equals(getType(), that.getType());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getDatabase(), getTimeDbCreation(), getClassifier(), getNamespace(), getType());
    }

    public org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry asPgEntity() {
        org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry clone = new org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry();
        clone.setId(this.id);
        clone.setDatabase(this.database.asPgEntity(clone));
        clone.setTimeDbCreation(this.timeDbCreation);
        clone.setClassifier(this.classifier);
        clone.setNamespace(this.namespace);
        clone.setType(this.type);
        return clone;
    }

    public org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry asPgEntity(org.qubership.cloud.dbaas.entity.pg.Database copy) {
        org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry clone = new org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry();
        clone.setId(this.id);
        clone.setDatabase(copy);
        clone.setTimeDbCreation(this.timeDbCreation);
        clone.setClassifier(this.classifier);
        clone.setNamespace(this.namespace);
        clone.setType(this.type);
        return clone;
    }

    private interface IgnoredDelegates {
        org.qubership.cloud.dbaas.entity.pg.Database asPgEntity();
    }
}
