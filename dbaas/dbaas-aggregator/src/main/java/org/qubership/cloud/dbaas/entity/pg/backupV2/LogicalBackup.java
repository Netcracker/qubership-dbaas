package org.qubership.cloud.dbaas.entity.pg.backupV2;

import jakarta.persistence.*;
import lombok.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.qubership.cloud.dbaas.converter.LogicalBackupStatusConverter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "logical_backup")
@Table(name = "v2_logical_backup")
public class LogicalBackup {

    @Id
    @GeneratedValue
    @Schema(description = "A unique identifier of the logical backup process.", required = true)
    private UUID id;

    @Column(name = "logical_backup_name")
    private String logicalBackupName;

    @ManyToOne
    @JoinColumn(name = "backup_name")
    private Backup backup;

    @Column(name = "adapter_id")
    private String adapterId;

    private String type;

    @NonNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Convert(converter = LogicalBackupStatusConverter.class)
    private LogicalBackupStatus status;

    @ToString.Exclude
    @OneToMany(mappedBy = "logicalBackup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<BackupDatabase> backupDatabases;

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        LogicalBackup that = (LogicalBackup) o;
        return Objects.equals(id, that.id)  && Objects.equals(adapterId, that.adapterId) && Objects.equals(type, that.type) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, adapterId, type, status);
    }
}
