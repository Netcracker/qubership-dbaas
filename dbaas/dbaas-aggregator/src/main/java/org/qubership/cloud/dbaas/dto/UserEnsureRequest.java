package org.qubership.cloud.dbaas.dto;

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
