package com.netcracker.it.dbaas.entity;

import com.netcracker.it.dbaas.helpers.ConnectionPropertiesUtils;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class DatabaseV3 {
    private String namespace;
    private String type;
    private String name;
    private String id;
    private List<Map<String, Object>> connectionProperties;
    private Map<String, Object> classifier;
    private Map<String, Object> settings;
    private Boolean backupDisabled;
    private String physicalDatabaseId;
    private List<DbResource> resources;
    private boolean externallyManageable;
    private List<String> dbOwnerRoles;

    public Map<String, Object> getConnectionPropertyByRole(Role role) {
        return ConnectionPropertiesUtils.getConnectionProperties(connectionProperties, role).get();
    }
}
