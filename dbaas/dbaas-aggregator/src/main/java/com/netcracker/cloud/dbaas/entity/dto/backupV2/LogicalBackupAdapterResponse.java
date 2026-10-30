package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Status of a logical backup")
public class LogicalBackupAdapterResponse {
    @Schema(description = "Status of the backup: notStarted, inProgress, completed or failed")
    private String status;
    @Schema(description = "Error message if the backup failed")
    private String errorMessage;
    @Schema(description = "Logical backup name in the adapter")
    private String backupId;
    @Schema(description = "Creation time of the backup")
    private Instant creationTime;
    @Schema(description = "Completion time of the backup")
    private Instant completionTime;
    @Schema(description = "Storage name of the backup")
    private String storageName;
    @Schema(description = "Path of the backup in the storage")
    private String blobPath;
    @Schema(description = "Status of each backed up database")
    private List<BackupDatabaseResponse> databases;

    @Data
    @Builder
    @Schema(description = "Status of a backed up database")
    public static class BackupDatabaseResponse {
        @Schema(description = "Name of the database")
        private String databaseName;
        @Schema(description = "Status of the database backup: notStarted, inProgress, completed or failed")
        private String status;
        @Schema(description = "Size of the backup in bytes")
        private long size;
        @Schema(description = "Duration of the database backup")
        private long duration;
        @Schema(description = "Path of the database backup")
        private String path;
        @Schema(description = "Error message if the database backup failed")
        private String errorMessage;
        @Schema(description = "Creation time of the backup")
        private Instant creationTime;
    }
}
