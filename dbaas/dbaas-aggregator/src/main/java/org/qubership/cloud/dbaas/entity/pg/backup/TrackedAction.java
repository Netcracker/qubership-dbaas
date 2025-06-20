package org.qubership.cloud.dbaas.entity.pg.backup;

import org.qubership.cloud.dbaas.converter.MapConverter;
import org.qubership.cloud.dbaas.dto.backup.Status;
import jakarta.persistence.*;
import lombok.Data;

import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Data
@Entity(name = "TrackedAction")
@Table(name = "tracked_action")
public class TrackedAction {

    public TrackedAction() {
        trackId = UUID.randomUUID().toString();
    }

    @Id
    @Column(name = "track_id")
    private String trackId;

    @Enumerated(value = EnumType.STRING)
    private Status status;

    @Enumerated(value = EnumType.STRING)
    private Action action;

    @Column(name = "adapter_id")
    private String adapterId;

    @Convert(converter = MapConverter.class)
    private Map<String, Object> details;

    @Column(name = "changed_name_db")
    @Convert(converter = MapConverter.class)
    private Map<String, String> changedNameDb;

    @Column(name = "track_path")
    private String trackPath;

    private Boolean finished = false;

    @Column(name = "created_time_ms")
    private Long createdTimeMs;

    @Column(name = "when_started")
    private Date whenStarted;

    @Column(name = "when_checked")
    private Date whenChecked;

    @Column(name = "when_finished")
    private Date whenFinished;


    public boolean useTrackPath() {
        return !StringUtils.isEmpty(trackPath);
    }

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
