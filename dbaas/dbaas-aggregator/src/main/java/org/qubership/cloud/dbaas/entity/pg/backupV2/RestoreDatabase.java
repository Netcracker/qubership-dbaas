package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Data
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

    @OneToOne
    @JoinColumn(name = "backup_db_id")
    private BackupDatabase backupDatabase;

    private String name;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<SortedMap<String, Object>> classifiers;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<User> users;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> resources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}
