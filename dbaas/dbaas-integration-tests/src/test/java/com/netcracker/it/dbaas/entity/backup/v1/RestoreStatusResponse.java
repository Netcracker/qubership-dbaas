package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RestoreStatusResponse {
    private RestoreStatus status;
    private Integer total;
    private Integer completed;
    private String errorMessage;
}
