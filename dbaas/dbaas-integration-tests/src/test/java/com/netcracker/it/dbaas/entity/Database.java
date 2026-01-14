package com.netcracker.it.dbaas.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class Database {
    private String namespace;
    private String type;
    private String name;
    private String id;
    private Map<String, Object> connectionProperties;
    private Map<String, Object> classifier;
    private Map<String, Object> settings;
    private Boolean backupDisabled;
    private String adapterId;
    private List<DbResource> resources;
    private boolean externallyManageable;
    private List<String> dbOwnerRoles;

    public String getConnectionPropertyAsSting (String property) {
        return (String) connectionProperties.get(property);
    }

    public Integer getConnectionPropertyAsInt (String property) {
        return (Integer) connectionProperties.get(property);
    }
}
