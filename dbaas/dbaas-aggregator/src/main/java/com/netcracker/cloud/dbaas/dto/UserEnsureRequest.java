package com.netcracker.cloud.dbaas.dto;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserEnsureRequest {
    private String dbName;
    private String password;
}
