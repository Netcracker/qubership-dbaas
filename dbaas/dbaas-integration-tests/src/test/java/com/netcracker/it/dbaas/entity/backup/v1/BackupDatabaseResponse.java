package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Data
@AllArgsConstructor
public class BackupDatabaseResponse {
    private UUID id;
    private String name;
    private List<SortedMap<String, Object>> classifiers;
    private Map<String, Object> settings;
    private List<User> users;
    private boolean configurational;
    private BackupTaskStatus status;
    private long size;
    private long duration;
    private String path;
    private String errorMessage;
    private Instant creationTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}

