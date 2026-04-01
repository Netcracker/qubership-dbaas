package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
public class RestoreDatabaseResponse {

    private UUID id;

    private String logicalBackupId;

    private String name;

    private List<ClassifierResponse> classifiers;

    private List<User> users;

    private Map<String, Object> settings;
    private Map<String, Object> resources;

    private String bgVersion;

    private RestoreTaskStatus status;

    private long duration;

    private String path;

    private String errorMessage;

    private Instant creationTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}
