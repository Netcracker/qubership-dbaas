package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.entity.pg.backup.DatabasesBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceRestoration;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import jakarta.annotation.Nonnull;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
@RequiredArgsConstructor
@NoArgsConstructor(force = true)
public class NamespaceBackupResponse {
    @Schema(description = "A unique identifier of the backup process. Backup process is associated with this id.", required = true)
    @NonNull
    private UUID id;

    @Schema(description = "List of adapters with backup information.")
    private List<DatabasesBackup> backups;

    @Schema(description = "List of errors that can occur during backup process.")
    @Convert(converter = ListConverter.class)
    private List<String> failReasons = new ArrayList<>();

    @Schema(description = "This parameter specifies project namespace whose databases are needed to save.", required = true)
    @NonNull
    private String namespace;

    @Schema(description = "List of backup databases.", required = true)
    @Nonnull
    private List<DatabaseResponse> databases;

    @Schema(description = "The object stores a restoring information related to this backup.")
    private List<NamespaceRestoration> restorations;

    @Schema(description = "Data of backup process creation.")
    private Date created = new Date();

    @Schema(description = "Status of backup process. This field may contain: FAIL, ACTIVE, PROCEEDING, RESTORING, INVALIDATED, DELETION_FAILED")
    @Enumerated(value = EnumType.STRING)
    private NamespaceBackup.Status status = NamespaceBackup.Status.PROCEEDING;


}
