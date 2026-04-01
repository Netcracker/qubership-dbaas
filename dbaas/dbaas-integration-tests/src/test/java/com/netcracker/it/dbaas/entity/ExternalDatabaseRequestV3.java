package com.netcracker.it.dbaas.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.util.List;
import java.util.Map;


@Data
@Builder
public class ExternalDatabaseRequestV3 {
    @NonNull
    private Map<String, Object> classifier;
    private List<Map<String, Object>> connectionProperties;
    @NonNull
    private String type;
    @NonNull
    private String dbName;
    private String username;
    private String password;
    private String originService;
    private String userRole;
    private Boolean updateConnectionProperties;
}
