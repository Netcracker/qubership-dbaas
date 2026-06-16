package com.netcracker.cloud.dbaas.entity.pg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDatabaseRegistry;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Delegate;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.*;

// lastRotatedAt is a PG-only marker, not part of the entity state mirrored to the H2 cache (the H2 entity
// has no such column, and DbaasDatabaseRegistryStabilityTest asserts PG and H2 serialize identically). It is
// excluded from JSON here so the entity's serialized form is unchanged; the changed-databases feed exposes
// the value via its own DTO (ChangedDatabaseResponse), not the entity.
@JsonIgnoreProperties("lastRotatedAt")
@Data
@Entity(name = "DatabaseRegistry")
@Table(name = "classifier")
@ToString(callSuper = true)
public class DatabaseRegistry extends AbstractDatabaseRegistry {

    public DatabaseRegistry() {
        this.id = UUID.randomUUID();
    }

    // @JsonCreator with a named param keeps `database` a property-based creator (as Lombok's former
    // @AllArgsConstructor did), so Jackson serializes it in the same position and, on deserialization,
    // constructs the database before the delegated setters (e.g. setName) run.
    @JsonCreator
    public DatabaseRegistry(@JsonProperty("database") Database database) {
        this.database = database;
    }

    @Schema(required = true, description = "It lists of database classifiers",
            ref = "Database")
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "database_id")
    @Delegate(excludes = {IgnoredDelegates.class, AbstractDatabaseRegistry.class})
    private Database database;

    @Schema(hidden = true, description = "Timestamp of the last credential change (password rotation or restore). " +
            "Used by the operator to pull rotation events; null until the first rotation.")
    @Column(name = "last_rotated_at")
    private OffsetDateTime lastRotatedAt;

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

    public com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry asH2Entity(com.netcracker.cloud.dbaas.entity.h2.Database db) {
        com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry copy = new com.netcracker.cloud.dbaas.entity.h2.DatabaseRegistry();
        copy.setId(this.id);
        copy.setTimeDbCreation(this.timeDbCreation);
        copy.setClassifier(this.classifier);
        copy.setNamespace(this.namespace);
        copy.setType(this.type);
        copy.setDatabase(db);
        return copy;
    }

    private interface IgnoredDelegates {
        com.netcracker.cloud.dbaas.entity.pg.Database asH2Entity(com.netcracker.cloud.dbaas.entity.h2.Database db);
    }
}
