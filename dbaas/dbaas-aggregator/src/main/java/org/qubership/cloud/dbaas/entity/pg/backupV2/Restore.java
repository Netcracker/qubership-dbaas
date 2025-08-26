package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
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
    @Column(columnDefinition = "jsonb")
    private String filters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String mapping;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private RestoreStatus status;

    @ToString.Exclude
    @OneToMany(mappedBy = "restore", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<LogicalRestore> logicalRestores;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Restore restore)) return false;
        return Objects.equals(name, restore.name) && Objects.equals(backup, restore.backup) && Objects.equals(storageName, restore.storageName) && Objects.equals(blobPath, restore.blobPath) && Objects.equals(filters, restore.filters) && Objects.equals(mapping, restore.mapping) && Objects.equals(status, restore.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, backup, storageName, blobPath, filters, mapping, status);
    }
}
