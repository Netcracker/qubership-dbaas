package org.qubership.cloud.dbaas.entity.pg;

import org.qubership.cloud.dbaas.dto.ConnectionDescription;
import org.qubership.cloud.dbaas.dto.v3.RegisterDatabaseRequestV3;
import org.qubership.cloud.dbaas.entity.shared.AbstractDatabase;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.*;
import java.util.stream.Collectors;

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

    @Schema(required = true, description = "It lists resource which will be deleted when sending the request for delete a database",
            ref = "DbResource")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<DbResource> resources;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER,orphanRemoval=true)
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

    public Database(RegisterDatabaseRequestV3 request) {
        this.id = UUID.randomUUID();
        this.classifier = request.getClassifier();
        this.connectionProperties = request.getConnectionProperties();
        this.resources = request.getResources();
        this.namespace = request.getNamespace();
        this.type = request.getType();
        this.adapterId = request.getAdapterId();
        this.name = request.getName();
        this.timeDbCreation = new Date();
        this.backupDisabled = request.getBackupDisabled();
        this.externallyManageable = false;
        this.physicalDatabaseId = request.getPhysicalDatabaseId();

        DatabaseRegistry newDatabaseRegistry = new DatabaseRegistry();
        newDatabaseRegistry.setDatabase(this);
        newDatabaseRegistry.setTimeDbCreation(this.getTimeDbCreation());
        newDatabaseRegistry.setClassifier(request.getClassifier());
        newDatabaseRegistry.setNamespace(request.getNamespace());
        newDatabaseRegistry.setType(request.getType());
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(newDatabaseRegistry);
        this.setDatabaseRegistry(databaseRegistries);
    }

    public Database(Database database) {
        this.id = UUID.randomUUID();
        this.classifier = database.classifier == null ? null : new TreeMap<>(database.classifier);
        this.oldClassifier = database.getOldClassifier() != null ? new TreeMap<>(database.getOldClassifier()) : null;
        this.connectionProperties = database.getConnectionProperties().stream().map(HashMap::new).collect(Collectors.toList());
        this.resources = database.getResources().stream().map(DbResource::new).collect(Collectors.toList());
        this.namespace = database.namespace;
        this.type = database.type;
        this.adapterId = database.getAdapterId();
        this.name = database.getName();
        this.markedForDrop = database.isMarkedForDrop();
        this.timeDbCreation = new Date();
        this.backupDisabled = database.getBackupDisabled() != null && Boolean.valueOf(database.getBackupDisabled());
        this.settings = database.getSettings() != null ? new HashMap<>(database.getSettings()) : null;
        this.connectionDescription = database.getConnectionDescription() != null ? new ConnectionDescription(database.getConnectionDescription()) : null;
        this.externallyManageable = database.isExternallyManageable();
        this.dbState = new DbState(database.getDbState());
        this.physicalDatabaseId = database.getPhysicalDatabaseId();
        List<DatabaseRegistry> registries = new ArrayList<>();
        for (DatabaseRegistry registry : database.getDatabaseRegistry()) {
            registries.add(new DatabaseRegistry(this,
                    this.getTimeDbCreation(), new TreeMap<>(registry.getClassifier()), registry.getNamespace(), registry.getType()));
        }
        this.databaseRegistry = registries;
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

    public org.qubership.cloud.dbaas.entity.h2.Database asH2Entity() {
        org.qubership.cloud.dbaas.entity.h2.Database copy = new org.qubership.cloud.dbaas.entity.h2.Database();
        copy.setId(this.id);
        copy.setOldClassifier(this.oldClassifier);
        copy.setClassifier(this.classifier);
        copy.setConnectionProperties(this.connectionProperties);
        if (resources != null) {
            copy.setResources(this.resources.stream().map(r -> r.asH2Entity()).toList());
        }
        if (databaseRegistry != null) {
            copy.setDatabaseRegistry(this.databaseRegistry.stream().map(dr -> dr.asH2Entity(copy)).toList());
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
        copy.setDbState(this.dbState.asH2Entity());
        copy.setPhysicalDatabaseId(this.physicalDatabaseId);
        return copy;
    }
}
