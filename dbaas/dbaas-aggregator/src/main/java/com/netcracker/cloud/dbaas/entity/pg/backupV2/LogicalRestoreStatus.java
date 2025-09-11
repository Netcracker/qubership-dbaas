package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import com.netcracker.cloud.dbaas.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicalRestoreStatus {

    private Status status = Status.NOT_STARTED;
    private String errorMessage;
    private LocalDateTime creationTime;
    private LocalDateTime completionTime;

    private List<LogicalRestoreStatus.Database> databases = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Database {
        private String previousDatabaseName;
        private String databaseName;
        private Status status;
        private Integer size;
        private String duration;
        private String path;
        private String errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LogicalRestoreStatus that)) return false;
        return status == that.status && Objects.equals(errorMessage, that.errorMessage) && Objects.equals(creationTime, that.creationTime) && Objects.equals(completionTime, that.completionTime) && Objects.equals(databases, that.databases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, errorMessage, creationTime, completionTime, databases);
    }
}
