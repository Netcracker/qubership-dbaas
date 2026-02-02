package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity
@Table(name = "restore_logical")
public class LogicalRestore {

    @Id
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private RestoreTaskStatus status = RestoreTaskStatus.NOT_STARTED;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "creation_time")
    private Instant creationTime;

    @Column(name = "completion_time")
    private Instant completionTime;

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
