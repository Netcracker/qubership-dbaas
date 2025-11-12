package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.netcracker.cloud.dbaas.enums.BackupTaskStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity
@Table(name = "backup_database")
public class BackupDatabase {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne
    @JsonBackReference
    @JoinColumn(name = "logical_backup_id")
    private LogicalBackup logicalBackup;

    private String name;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<SortedMap<String, Object>> classifiers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> settings;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<User> users;

    private boolean configurational;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private BackupTaskStatus status = BackupTaskStatus.NOT_STARTED;

    private long size;

    private long duration;

    private String path;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "creation_time")
    private Instant creationTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}
