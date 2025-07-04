package org.qubership.cloud.dbaas.entity.pg;

import org.qubership.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Delegate;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.*;

@Data
@Entity(name = "DatabaseRegistry")
@Table(name = "classifier")
@ToString(callSuper = true)
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
    private Database database;

    public DatabaseRegistry(Database database, Date timeDbCreation, SortedMap<String, Object> classifier, String namespace, String type) {
        super(UUID.randomUUID(), timeDbCreation, classifier, namespace, type);
        this.database = database;
    }

    public DatabaseRegistry(DatabaseRegistry databaseRegistry, String namespace) {
        this.id = UUID.randomUUID();
        this.database = databaseRegistry.getDatabase();
        this.timeDbCreation = new Date();
        SortedMap<String, Object> classifier1 = new TreeMap<>(databaseRegistry.getClassifier());
        classifier1.put("namespace", namespace);
        this.classifier = classifier1;
        this.namespace = namespace;
        this.type = databaseRegistry.getType();
    }

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

    public org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry asH2Entity(org.qubership.cloud.dbaas.entity.h2.Database db) {
        org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry copy = new org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry();
        copy.setId(this.id);
        copy.setTimeDbCreation(this.timeDbCreation);
        copy.setClassifier(this.classifier);
        copy.setNamespace(this.namespace);
        copy.setType(this.type);
        copy.setDatabase(db);
        return copy;
    }

    private interface IgnoredDelegates {
        org.qubership.cloud.dbaas.entity.pg.Database asH2Entity(org.qubership.cloud.dbaas.entity.h2.Database db);
    }
}
