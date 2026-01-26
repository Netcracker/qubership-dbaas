package com.netcracker.it.dbaas.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.util.*;

@Data
@Builder
public class ExternalDatabaseRequest {
    @NonNull
    private Map<String, Object> classifier;
    private Map<String, Object> connectionProperties;
    @NonNull
    private String type;
    @NonNull
    private String dbName;
    @NonNull
    private List<DbResource> resources;
    private String username;
    private String password;
}
