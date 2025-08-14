package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;


@Data
@NoArgsConstructor(force = true)
@Entity(name = "backup")
@Table(name = "v2_backup")
public class Backup {

    @Id
    @NotNull
    @Schema(description = "A unique identifier of the backup process. Backup process is associated with this name.", required = true)
    private String name;

    @NotNull
    @Column(name = "storage_name")
    private String storageName;

    @NotNull
    @Column(name = "blob_path")
    private String blobPath;

    @NotNull
    @Column(name = "external_database_strategy")
    private ExternalDatabaseStrategy externalDatabaseStrategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String filters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private BackupStatus status;

    @ToString.Exclude
    @OneToMany(mappedBy = "backup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<LogicalBackup> logicalBackups;

    public Backup(String name, String storageName, String blobPath, ExternalDatabaseStrategy externalDatabaseStrategy, String filters) {
        this.name = name;
        this.storageName = storageName;
        this.blobPath = blobPath;
        this.externalDatabaseStrategy = externalDatabaseStrategy;
        this.filters = filters;
    }
}
