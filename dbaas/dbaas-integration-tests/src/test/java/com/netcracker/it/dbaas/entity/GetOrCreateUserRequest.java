package com.netcracker.it.dbaas.entity;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GetOrCreateUserRequest {
    @NotNull
    private Map<String, Object> classifier;
    @NotNull
    private String logicalUserId;
    @NotNull
    private String type;
    private String physicalDbId;
    private String usernamePrefix;
    private String userRole;
}
