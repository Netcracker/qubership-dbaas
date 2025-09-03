package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class LogicalBackupStatusResponse {
    private Status status;
    private String errorMessage;
    private LocalDateTime creationTime;
    private LocalDateTime completionTime;
    private List<Database> databases;

    @Data
    @AllArgsConstructor
    public static class Database {
        private String databaseName;
        private Status status;
        private Integer size;
        private String duration;
        private String path;
        private String errorMessage;
    }
}
