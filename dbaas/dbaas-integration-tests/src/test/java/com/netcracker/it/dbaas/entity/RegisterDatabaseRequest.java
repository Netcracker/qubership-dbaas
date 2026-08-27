package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDatabaseRequest {

    @NotNull
    private Map<String, Object> classifier;

    @NotNull
    private List<ConnectionProperties> connectionProperties;

    @NotNull
    private List<DbResource> resources;

    @NotNull
    private String namespace;

    @NotNull
    private String type;

    @NotNull
    private String adapterId;

    @NotNull
    private String name;

    private Boolean backupDisabled = false;

    @Nullable
    private String physicalDatabaseId;

    @Nullable
    private String dbHost;

    public RegisterDatabaseRequest(Map<String, Object> classifier,
                                   List<ConnectionProperties> connectionProperties,
                                   List<DbResource> resources,
                                   String namespace,
                                   String adapterId,
                                   String type,
                                   String name,
                                   @Nullable String physicalDatabaseId,
                                   @Nullable String dbHost) {
        this.classifier = classifier;
        this.connectionProperties = connectionProperties;
        this.resources = resources;
        this.namespace = namespace;
        this.adapterId = adapterId;
        this.type = type;
        this.name = name;
        this.physicalDatabaseId = physicalDatabaseId;
        this.dbHost = dbHost;
    }
}