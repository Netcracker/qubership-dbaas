package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.BackupTaskStatus;
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
public class LogicalBackupAdapterResponse {
    private String status;
    private String errorMessage;
    private String backupId;
    private LocalDateTime creationTime;
    private LocalDateTime completionTime;
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
        private LocalDateTime creationTime;
    }
}
