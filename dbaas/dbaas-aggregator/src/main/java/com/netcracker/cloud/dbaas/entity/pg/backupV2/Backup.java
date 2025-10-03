package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.enums.Status;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "backup")
@Table(name = "v2_backup")
public class Backup {

    @Id
    @NotNull
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

    @ToString.Exclude
    @OneToMany(mappedBy = "backup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<LogicalBackup> logicalBackups;

    @ToString.Exclude
    @OneToMany(mappedBy = "backup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BackupExternalDatabase> externalDatabases;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private Status status = Status.NOT_STARTED;

    private Integer total;

    private Integer completed;

    private Long size;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "attempt_count")
    private int attemptCount;

    private boolean imported;

    public Backup(String name, String storageName, String blobPath, ExternalDatabaseStrategy externalDatabaseStrategy, String filters) {
        this.name = name;
        this.storageName = storageName;
        this.blobPath = blobPath;
        this.externalDatabaseStrategy = externalDatabaseStrategy;
        this.filters = filters;
    }
}
