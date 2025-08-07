package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import lombok.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;


@Data
@RequiredArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "backup")
@Table(name = "v2_backup")
public class Backup {

    @Id
    @NonNull
    @Schema(description = "A unique identifier of the backup process. Backup process is associated with this name.", required = true)
    private String name;

    @Column(name = "storage_name")
    private String storageName;

    @Column(name = "blob_path")
    private String blobPath;

    @Column(name = "external_database_strategy")
    private String externalDatabaseRegistry;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String filters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private BackupStatus status;

    @ToString.Exclude
    @OneToMany(mappedBy = "backup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LogicalBackup> logicalBackups;

    public Backup(String name, String storageName, String blobPath, String externalDatabaseRegistry,  String filters) {
        this.name = name;
        this.storageName = storageName;
        this.blobPath = blobPath;
        this.externalDatabaseRegistry = externalDatabaseRegistry;
        this.filters = filters;
    }
}
