package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Entity(name = "restore_logical")
@Table(name = "restore_logical")
public class RestoreLogical {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "restore_logical_name")
    private String restoreLogicalName;

    @ManyToOne
    @JoinColumn(name = "restore_name")
    private Restore restore;

    @Column(name = "adapter_id")
    private String adapterId;

    private String type;

    @ToString.Exclude
    @OneToMany(mappedBy = "restoreLogical", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<RestoreDatabase> restoreDatabases;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private RestoreTaskStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "creation_time")
    private Instant creationTime;

    @Column(name = "completion_time")
    private Instant completionTime;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RestoreLogical that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(restoreLogicalName, that.restoreLogicalName) && Objects.equals(restore, that.restore) && Objects.equals(adapterId, that.adapterId) && Objects.equals(type, that.type) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, restoreLogicalName, restore, adapterId, type, status);
    }
}
