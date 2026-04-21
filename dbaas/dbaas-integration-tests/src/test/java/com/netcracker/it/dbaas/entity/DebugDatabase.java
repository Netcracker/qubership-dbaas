package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DebugDatabase {

    private String id;
    private Map<String, Object> classifier;
    private List<ConnectionProperties> connectionProperties;
    private String namespace;
    private String type;
    private String adapterId;
    private String name;
    private Boolean markedForDrop;
    private String timeDbCreation;
    private Boolean backupDisabled;
    private Boolean externallyManageable;
    private String physicalDatabaseId;
    private List<Map<String, Object>> databaseRegistry;
    private List<DbResource> resources;
    private DbState dbState;
}
