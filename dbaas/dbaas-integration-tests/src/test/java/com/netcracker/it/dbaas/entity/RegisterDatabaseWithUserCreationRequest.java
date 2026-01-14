package com.netcracker.it.dbaas.entity;

import lombok.Data;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Data
public class RegisterDatabaseWithUserCreationRequest {

    @NotNull
    private Map<String, Object> classifier;

    @NotNull
    private String type;

    @NotNull
    private String name;

    private Boolean backupDisabled = false;

    @Nullable
    private String physicalDatabaseId;

    @Nullable
    private String dbHost;

    public RegisterDatabaseWithUserCreationRequest(Map<String, Object> classifier,
                                                   String type, String name,
                                                   String physicalDatabaseId, String dbHost) {
        this.classifier = classifier;
        this.type = type;
        this.name = name;
        this.physicalDatabaseId = physicalDatabaseId;
        this.dbHost = dbHost;
    }
}
