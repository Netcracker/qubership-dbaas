package org.qubership.cloud.dbaas.entity.pg.backup;

import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.converter.ListDatabaseConverter;
import org.qubership.cloud.dbaas.converter.ListDatabaseRegisterConverter;
import org.qubership.cloud.dbaas.dto.NamespaceBackupDTO;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
@RequiredArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "NamespaceBackup")
@Table(name = "namespace_backup")
public class NamespaceBackup {
    @Schema(description = "A unique identifier of the backup process. Backup process is associated with this id.", required = true)
    @Id
    @NonNull
    private UUID id;

    @Schema(description = "List of adapters with backup information.")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<DatabasesBackup> backups;

    @Schema(description = "List of errors that can occur during backup process.")
    @Column(name = "fail_reasons")
    @Convert(converter = ListConverter.class)
    private List<String> failReasons = new ArrayList<>();

    @Schema(description = "This parameter specifies project namespace whose databases are needed to save.", required = true)
    @NonNull
    private String namespace;

    @Schema(description = "List of backup databases.", required = true)
    @NonNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = ListDatabaseConverter.class)
    private List<Database> databases;

    @Schema(description = "List of backup database registers", required = true)
    @NonNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = ListDatabaseRegisterConverter.class)
    @Column(name = "database_registries")
    private List<DatabaseRegistry> databaseRegistries;

    @Schema(description = "The object stores a restoring information related to this backup.")
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<NamespaceRestoration> restorations;

    @Schema(description = "Data of backup process creation.")
    private Date created = new Date();

    public enum Status {
        FAIL, ACTIVE, PROCEEDING, RESTORING, INVALIDATED, DELETION_FAILED
    }

    @Schema(description = "Status of backup process. This field may contain: FAIL, ACTIVE, PROCEEDING, RESTORING, INVALIDATED, DELETION_FAILED")
    @Enumerated(value = EnumType.STRING)
    private Status status = Status.PROCEEDING;

    public boolean canRestore() {
        return Status.ACTIVE.equals(status);
    }

    public boolean canBeDeleted() {
        return !Status.RESTORING.equals(status) &&
                !Status.PROCEEDING.equals(status);
    }

    public NamespaceBackup(NamespaceBackupDTO namespaceBackupDTO) {
        this.id = namespaceBackupDTO.getId();
        this.backups = namespaceBackupDTO.getBackups();
        this.failReasons = namespaceBackupDTO.getFailReasons();
        this.namespace = namespaceBackupDTO.getNamespace();
        this.databaseRegistries = namespaceBackupDTO.getDatabaseRegistries();
        this.databases = namespaceBackupDTO.getDatabases();
        this.restorations = namespaceBackupDTO.getRestorations();
        this.created = namespaceBackupDTO.getCreated();
        this.status = namespaceBackupDTO.getStatus();
    }
}
