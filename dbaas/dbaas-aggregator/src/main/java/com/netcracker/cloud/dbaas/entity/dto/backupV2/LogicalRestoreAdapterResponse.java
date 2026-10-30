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
@Schema(description = "Status of a logical restore")
public class LogicalRestoreAdapterResponse {
    @Schema(description = "Status of the restore: notStarted, inProgress, completed or failed")
    private String status;
    @Schema(description = "Error message if the restore failed")
    private String errorMessage;
    @Schema(description = "Logical restore name in the adapter")
    private String restoreId;
    @Schema(description = "Creation time of the restore")
    private Instant creationTime;
    @Schema(description = "Completion time of the restore")
    private Instant completionTime;
    @Schema(description = "Storage name of the backup")
    private String storageName;
    @Schema(description = "Path of the backup in the storage")
    private String blobPath;
    @Schema(description = "Status of each restored database")
    private List<LogicalRestoreAdapterResponse.RestoreDatabaseResponse> databases;

    @Data
    @Builder
    @Schema(description = "Status of a restored database")
    public static class RestoreDatabaseResponse {
        @Schema(description = "Name of the database in the backup")
        private String previousDatabaseName;
        @Schema(description = "Name of the restored database")
        private String databaseName;
        @Schema(description = "Status of the database restore: notStarted, inProgress, completed or failed")
        private String status;
        @Schema(description = "Path of the restored database")
        private String path;
        @Schema(description = "Name of the microservice")
        private String microserviceName;
        @Schema(description = "Name of the namespace")
        private String namespace;
        @Schema(description = "Prefix of the database")
        private String prefix;
        @Schema(description = "Duration of the database restore")
        private long duration;
        @Schema(description = "Error message if the database restore failed")
        private String errorMessage;
        @Schema(description = "Creation time of the restore")
        private Instant creationTime;
    }
}
