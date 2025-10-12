package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicalRestoreAdapterResponse {
    private RestoreTaskStatus status;
    private String errorMessage;
    private String logicalRestoreName;
    private LocalDateTime creationTime;
    private LocalDateTime completionTime;
    private String storageName;
    private String blobPath;
    private List<LogicalRestoreAdapterResponse.RestoreDatabaseResponse> databases;

    @Data
    @Builder
    public static class RestoreDatabaseResponse {
        private String microserviceName;
        private String namespace;
        private String prefix;
        private String previousDatabaseName;
        private String databaseName;
        private RestoreTaskStatus status;
        private long duration;
        private String path;
        private String errorMessage;
        private LocalDateTime creationTime;
    }
}
