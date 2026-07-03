package com.netcracker.cloud.dbaas.dto.userrestore;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SuccessfulRestoreUsersResponse {
    private String message;
}
