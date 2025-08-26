package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.qubership.cloud.dbaas.converter.LogicalRestoreStatusConverter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "LogicalRestore")
@Table(name = "v2_logical_restore")
public class LogicalRestore {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "logical_restore_name")
    private String logicalRestoreName;

    @ManyToOne
    @JoinColumn(name = "restore_name")
    private Restore restore;

    @Column(name = "adapter_id")
    private String adapterId;

    private String type;

    @ToString.Exclude
    @OneToMany(mappedBy = "logicalRestore", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<RestoreDatabase> restoreDatabases;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Convert(converter = LogicalRestoreStatusConverter.class)
    private LogicalRestoreStatus status;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LogicalRestore that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(logicalRestoreName, that.logicalRestoreName) && Objects.equals(restore, that.restore) && Objects.equals(adapterId, that.adapterId) && Objects.equals(type, that.type) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, logicalRestoreName, restore, adapterId, type, status);
    }
}
