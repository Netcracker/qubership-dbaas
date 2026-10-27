package com.netcracker.cloud.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "The request to ensure database user")
public class UserEnsureRequest {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Database name to create user.")
    private String dbName;
    @Schema(description = "It is password for user which will be used for it's creation or would be changed to. If password is absent it'll be generated.")
    private String password;
}
