package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@NoArgsConstructor
public class ExternalDatabaseResponseV3 {

    private UUID id;
    private SortedMap<String, Object> classifier;

    private List<Map<String, Object>> connectionProperties;

    private List<DbResource> resources;
    private String namespace;
    private String type;
    private String name;
    private Date timeDbCreation;
    private boolean externallyManageable;
}
