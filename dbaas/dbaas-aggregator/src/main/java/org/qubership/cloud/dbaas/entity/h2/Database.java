package org.qubership.cloud.dbaas.entity.h2;

import org.qubership.cloud.dbaas.entity.shared.AbstractDatabase;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.hibernate.collection.spi.PersistentBag;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils.toStringWithMaskedPassword;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity(name = "Database")
@Table(name = "database")
public class Database extends AbstractDatabase {

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER, mappedBy = "database")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<DatabaseRegistry> databaseRegistry;

    @Schema(required= true, description = "It lists resource which will be deleted when sending the request for delete a database",
            ref = "DbResource")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<DbResource> resources;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private DbState dbState = new DbState();

    public DbState getDbState() {
        if (dbState == null) {
            dbState = new DbState();
        }
        return dbState;
    }

    public void addAllResources(List<DbResource> dbResourceForNewRole) {
        if (this.resources == null) {
            this.resources = new ArrayList<>();
        }
        this.resources.addAll(dbResourceForNewRole);
    }

    @Override
    public String toString() {
        return "Database{" +
                "id=" + id +
                ", oldClassifier=" + oldClassifier +
                ", classifier=" + classifier +
                ", connectionProperties=" + toStringWithMaskedPassword(connectionProperties) +
                ", resources=" + resources +
                ", namespace='" + namespace + '\'' +
                ", type='" + type + '\'' +
                ", adapterId='" + adapterId + '\'' +
                ", name='" + name + '\'' +
                ", markedForDrop=" + markedForDrop +
                ", timeDbCreation=" + timeDbCreation +
                ", backupDisabled=" + backupDisabled +
                ", settings=" + settings +
                ", connectionDescription=" + connectionDescription +
                ", warnings=" + warnings +
                ", externallyManageable=" + externallyManageable +
                ", dbState=" + dbState +
                ", physicalDatabaseId='" + physicalDatabaseId + '\'' +
                ", bgVersion='" + bgVersion + '\'' +
                '}';
    }

    private org.qubership.cloud.dbaas.entity.pg.Database asPgEntityBase() {
        org.qubership.cloud.dbaas.entity.pg.Database copy = new org.qubership.cloud.dbaas.entity.pg.Database();
        copy.setId(this.id);
        copy.setOldClassifier(this.oldClassifier);
        copy.setClassifier(this.classifier);
        copy.setConnectionProperties(this.connectionProperties);
        if (resources != null) {
            copy.setResources(this.resources.stream().map(r -> r.asPgEntity()).toList());
        }
        copy.setNamespace(this.namespace);
        copy.setType(this.type);
        copy.setAdapterId(this.adapterId);
        copy.setName(this.name);
        copy.setMarkedForDrop(this.markedForDrop);
        copy.setTimeDbCreation(this.timeDbCreation);
        copy.setBackupDisabled(this.backupDisabled);
        copy.setDbOwnerRoles(this.dbOwnerRoles);
        copy.setSettings(this.settings);
        copy.setConnectionDescription(this.connectionDescription);
        copy.setWarnings(this.warnings);
        copy.setExternallyManageable(this.externallyManageable);
        copy.setBgVersion(this.bgVersion);
        copy.setDbState(this.dbState.asPgEntity());
        copy.setPhysicalDatabaseId(this.physicalDatabaseId);
        return copy;
    }

    public org.qubership.cloud.dbaas.entity.pg.Database asPgEntity() {
        org.qubership.cloud.dbaas.entity.pg.Database copy = asPgEntityBase();
        copy.setDatabaseRegistry(new PersistentBag(null, this.databaseRegistry.stream().map(dr -> dr.asPgEntity(copy)).toList()));
        return copy;
    }

    public org.qubership.cloud.dbaas.entity.pg.Database asPgEntity(org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry clone) {
        org.qubership.cloud.dbaas.entity.pg.Database copy = asPgEntityBase();
        copy.setDatabaseRegistry(new PersistentBag(null, this.databaseRegistry.stream().map(dr -> {
            if (dr.getId().equals(clone.getId())) {
                return clone;
            } else {
                return dr.asPgEntity(copy);
            }
        }).toList()));
        return copy;
    }
}
