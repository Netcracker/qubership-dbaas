package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UpdateConnectionPropertiesRequest {
    Map<String, Object> classifier;
    Map<String, Object> connectionProperties;
    String physicalDatabaseId;
    List<DbResource> resources;
    String dbName;
}
