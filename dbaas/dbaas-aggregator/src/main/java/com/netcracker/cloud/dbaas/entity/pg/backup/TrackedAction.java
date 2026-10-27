package com.netcracker.cloud.dbaas.entity.pg.backup;

import com.netcracker.cloud.dbaas.converter.MapConverter;
import com.netcracker.cloud.dbaas.dto.backup.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Data
@Entity(name = "TrackedAction")
@Table(name = "tracked_action")
@Schema(description = "Status of a backup or restore action")
public class TrackedAction {

    public TrackedAction() {
        trackId = UUID.randomUUID().toString();
    }

    @Id
    @Column(name = "track_id")
    @Schema(description = "Track identifier")
    private String trackId;

    @Enumerated(value = EnumType.STRING)
    @Schema(description = "Status of the action")
    private Status status;

    @Enumerated(value = EnumType.STRING)
    @Schema(description = "Type of the action: backup or restore")
    private Action action;

    @Column(name = "adapter_id")
    @Schema(description = "Identifier of the adapter which performed the action")
    private String adapterId;

    @Convert(converter = MapConverter.class)
    @Schema(description = "Details of the action")
    private Map<String, Object> details;

    @Column(name = "changed_name_db")
    @Convert(converter = MapConverter.class)
    @Schema(description = "Map of original database names to the names they were restored under")
    private Map<String, String> changedNameDb;

    @Column(name = "track_path")
    @Schema(description = "Path to the track log, used as track log instead of trackId when present")
    private String trackPath;

    @Schema(description = "Whether the action has finished")
    private Boolean finished = false;

    @Column(name = "created_time_ms")
    @Schema(description = "Time when the action was created, in milliseconds since epoch")
    private Long createdTimeMs;

    @Column(name = "when_started")
    @Schema(description = "Time when the action started")
    private Date whenStarted;

    @Column(name = "when_checked")
    @Schema(description = "Time when the action was last checked")
    private Date whenChecked;

    @Column(name = "when_finished")
    @Schema(description = "Time when the action finished")
    private Date whenFinished;


    public boolean useTrackPath() {
        return !StringUtils.isEmpty(trackPath);
    }

    @Schema(description = "Identifier of the track log: trackPath when present, otherwise trackId")
    public String getTrackLog() {
        return useTrackPath() ? trackPath : trackId;
    }

    public enum Action {
        BACKUP, RESTORE
    }

    public void setStatus(Status status) {
        this.status = status;
        if (status == Status.FAIL || status == Status.SUCCESS) {
            whenFinished = new Date();
            finished = true;
        }
    }

    public void setWhenStarted(Date date) {
        this.createdTimeMs = date.getTime();
        this.whenStarted = date;
    }
}
