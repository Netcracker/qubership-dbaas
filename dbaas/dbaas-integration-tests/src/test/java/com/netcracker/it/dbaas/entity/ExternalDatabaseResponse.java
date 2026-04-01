package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.*;

@Data
public class ExternalDatabaseResponse {
    private UUID id;
    private SortedMap<String, Object> classifier;
    private Map<String, Object> connectionProperties;
    private List<DbResource> resources;
    private String namespace;
    private String type;
    private String name;
    private Date timeDbCreation;
    private Boolean backupDisabled;
    private boolean externallyManageable;
}
