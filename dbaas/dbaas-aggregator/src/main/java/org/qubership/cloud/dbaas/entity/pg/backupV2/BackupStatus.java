package org.qubership.cloud.dbaas.entity.pg.backupV2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qubership.cloud.dbaas.enums.Status;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupStatus {
    private Status status = Status.NOT_STARTED;
    private Integer total;
    private Integer completed;
    private Long size;
    private String errorMessage;
}
