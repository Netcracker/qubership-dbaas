package com.netcracker.it.dbaas.entity;

import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class DatabaseResponse {
    private String namespace;
    private String type;
    private String name;
    private String id;
    private Map<String, Object> connectionProperties;
    private Map<String, Object> classifier;
    private Map<String, Object> settings;
    private Boolean backupDisabled;
    private String physicalDatabaseId;
    private List<DbResource> resources;
    private boolean externallyManageable;
    private List<String> dbOwnerRoles;

    public String getConnectionPropertyAsString(String property) {
        return (String) connectionProperties.get(property);
    }

    public Integer getConnectionPropertyAsInt (String property) {
        return (Integer) connectionProperties.get(property);
    }

    public String getName() {
        var nameValue = name;

        if (StringUtils.isBlank(nameValue) && "opensearch".equalsIgnoreCase(type)) {
            var resourcePrefix = getConnectionPropertyAsString("resourcePrefix");

            if (StringUtils.isNotBlank(resourcePrefix)) {
                nameValue = resourcePrefix;
            }
        }

        return nameValue;
    }
}