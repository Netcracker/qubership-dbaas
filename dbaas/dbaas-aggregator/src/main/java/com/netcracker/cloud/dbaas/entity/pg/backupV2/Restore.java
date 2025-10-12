package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "Restore")
@Table(name = "v2_restore")
public class Restore {

    @Id
    @NotNull
    private String name;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "backup_name")
    private Backup backup;

    @Column(name = "storage_name")
    private String storageName;

    @NotNull
    @Column(name = "blob_path")
    private String blobPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_criteria", columnDefinition = "jsonb")
    private FilterCriteriaEntity filterCriteria;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Mapping mapping;

    @ToString.Exclude
    @OneToMany(mappedBy = "restore", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<LogicalRestore> logicalRestores;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private RestoreStatus status = RestoreStatus.NOT_STARTED;

    private Integer total;

    private Integer completed;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "attempt_count")
    private int attemptCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Mapping {
        Map<String, String> namespaces;
        Map<String, String> tenants;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Restore restore)) return false;
        return Objects.equals(name, restore.name) && Objects.equals(backup, restore.backup) && Objects.equals(storageName, restore.storageName) && Objects.equals(blobPath, restore.blobPath) && Objects.equals(filterCriteria, restore.filterCriteria) && Objects.equals(mapping, restore.mapping) && Objects.equals(status, restore.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, backup, storageName, blobPath, filterCriteria, mapping, status);
    }
}
