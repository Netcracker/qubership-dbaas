package org.qubership.cloud.dbaas.entity.pg.backup;

import org.qubership.cloud.dbaas.converter.ListConverter;
import org.qubership.cloud.dbaas.dto.backup.Status;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity(name = "DatabasesBackup")
@Table(name = "databases_backup")
public class DatabasesBackup {

    @Id
    @GeneratedValue
    UUID id;

    @Schema(description = "This field contains the status of backup process of the specific adapter.")
    @Enumerated(value = EnumType.STRING)
    private Status status = Status.PROCEEDING;

    @Schema(description = "This field contains the adapter id.")
    @Column(name = "adapter_id")
    private String adapterId;

    @Schema(description = "Identifier of an adapter associated with backup process")
    @Column(name = "local_id")
    private String localId;

    @Schema(description = "Identifier for polling process specific adapter.")
    @Column(name = "track_id")
    private String trackId;

    @Schema(description = "Priority path for polling process.")
    @Column(name = "track_path")
    private String trackPath;

    @Schema(description = "List of databases' names")
    @Convert(converter = ListConverter.class)
    private List<String> databases;

    public DatabasesBackup(TrackedAction track) {
        this.localId = (String) track.getDetails().get("localId");
        this.trackId = track.getTrackId();
        this.trackPath = track.getTrackPath();
    }
}
