package com.netcracker.cloud.dbaas.entity.dto.backupV2;

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
    private String status;
    private String errorMessage;
    private String restoreId;
    private LocalDateTime creationTime;
    private LocalDateTime completionTime;
    private String storageName;
    private String blobPath;
    private List<LogicalRestoreAdapterResponse.RestoreDatabaseResponse> databases;

    @Data
    @Builder
    public static class RestoreDatabaseResponse {
        private String previousDatabaseName;
        private String databaseName;
        private String status;
        private String path;
        private String microserviceName;
        private String namespace;
        private String prefix;
        private long duration;
        private String errorMessage;
        private LocalDateTime creationTime;
    }
}
