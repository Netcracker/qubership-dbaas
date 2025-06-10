package org.qubership.cloud.dbaas.entity.pg.backup;

import org.qubership.cloud.dbaas.converter.MapConverter;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import jakarta.persistence.*;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@Entity(name = "RestoreResult")
@Table(name = "restore_result")
public class RestoreResult {

    @Id
    @GeneratedValue
    UUID id;

    @Schema(description = "This object contains information about restoring the specific adapter.")
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "databases_backup_id")
    private DatabasesBackup databasesBackup;

    @Schema(description = "The field contains the restore status of specific adapter.")
    @NonNull
    @Enumerated(value = EnumType.STRING)
    private Status status = Status.PROCEEDING;

    @NonNull
    @Column(name = "adapter_id")
    private String adapterId;

    @Schema(description = "This associative array contain database names, where key: \"old db name\", value: \"new db name\". This array will not be empty if the targetNamespace parameter will be passed and " +
            "it will be filled during restore to another namespace.")
    @Column(name = "changed_name_db")
    @Convert(converter = MapConverter.class)
    private Map<String, String> changedNameDb;
}
