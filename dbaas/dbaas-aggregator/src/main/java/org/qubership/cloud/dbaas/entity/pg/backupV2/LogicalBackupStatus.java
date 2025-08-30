package org.qubership.cloud.dbaas.entity.pg.backupV2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.cloud.dbaas.enums.Status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicalBackupStatus {

    private Status status = Status.NOT_STARTED;
    private String errorMessage;
    private LocalDateTime creationTime;
    private LocalDateTime completionTime;

    private List<Database> databases = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Database{
        private String databaseName;
        private Status status;
        private Integer size;
        private String duration;
        private String path;
        private String errorMessage;
    }
}
