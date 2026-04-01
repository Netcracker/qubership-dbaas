package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NonNull;

import java.util.Map;

@Data
public class RecreateDatabaseRequest {
    @NonNull
    private String type;
    @NonNull
    private Map<String, Object> classifier;
    @NonNull
    private String physicalDatabaseId;
}