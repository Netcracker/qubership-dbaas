package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BackupStatusResponse {
    private BackupStatus status;
    private Integer total;
    private Integer completed;
    private Long size;
    private String errorMessage;
}
