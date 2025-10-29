package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "RestoreDatabase")
@Table(name = "v2_restore_database")
public class RestoreDatabase {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "logical_restore_id")
    private LogicalRestore logicalRestore;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "backup_db_id")
    private BackupDatabase backupDatabase;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> resources;

    private String bgVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private RestoreTaskStatus status = RestoreTaskStatus.NOT_STARTED;

    private long duration;

    private String path;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "creation_time")
    private LocalDateTime creationTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}
