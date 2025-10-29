package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicalBackupAdapterResponse {
    private String status;
    private String errorMessage;
    private String backupId;
    private Instant creationTime;
    private Instant completionTime;
    private String storageName;
    private String blobPath;
    private List<BackupDatabaseResponse> databases;

    @Data
    @Builder
    public static class BackupDatabaseResponse {
        private String databaseName;
        private String status;
        private long size;
        private long duration;
        private String path;
        private String errorMessage;
        private Instant creationTime;
    }
}
