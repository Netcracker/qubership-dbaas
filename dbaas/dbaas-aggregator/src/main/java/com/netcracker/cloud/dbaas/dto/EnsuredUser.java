package com.netcracker.cloud.dbaas.dto;

import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "All information about created user or updated user")
public class EnsuredUser {
    @Schema(description = "Name of database which accessed by created or updated user, would be null if was not requested")
    private String name;
    @Schema(description = "Connection properties could be used to connect to database with created user. " +
            "Also connection properties must be the same as when creating DB")
    private Map<String, Object> connectionProperties;
    @Schema(description = "List of resources created during user creation")
    private List<DbResource> resources;
    @Schema(description = "Indicates whether the user was created by the request or already existed")
    private Boolean created;
}
