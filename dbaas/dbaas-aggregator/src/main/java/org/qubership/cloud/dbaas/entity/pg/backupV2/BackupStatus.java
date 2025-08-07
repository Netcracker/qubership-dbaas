package org.qubership.cloud.dbaas.entity.pg.backupV2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupStatus {
    private Status status;
    private Integer total;
    private Integer completed;
    private long size;
    private String errorMessage;
}
