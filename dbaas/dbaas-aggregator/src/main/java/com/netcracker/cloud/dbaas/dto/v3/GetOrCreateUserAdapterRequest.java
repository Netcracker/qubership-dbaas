package com.netcracker.cloud.dbaas.dto.v3;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@EqualsAndHashCode
@ToString
@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "The request to create database user")
public class GetOrCreateUserAdapterRequest {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Database name to create user.")
    private String dbName;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Database role name. For instance admin, rw or ro role.")
    private String role;
    @Schema(description = "Prefix for username.")
    private String userNamePrefix;

    public static final String EXAMPLE_REQUEST = """
            {
              "dbName": "inventory",
              "role": "rw",
              "userNamePrefix": "svc_inventory_"
            }
            """;
}
