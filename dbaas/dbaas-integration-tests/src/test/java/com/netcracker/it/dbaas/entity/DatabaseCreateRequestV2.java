package com.netcracker.it.dbaas.entity;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

@Builder
public class DatabaseCreateRequestV2  {

    @NonNull
    private final String dbOwner;

    @NonNull
    private final Map<String, Object> classifier;
    @NonNull
    private final String type;

    public static DatabaseCreateRequestV2Builder builder(Map<String, Object> classifier,
                                                         String type,
                                                         String dbOwner) {
        return new DatabaseCreateRequestV2Builder()
                .classifier(classifier)
                .type(type)
                .dbOwner(dbOwner);
    }

    private String namePrefix;
    private String dbName;
    private String username;
    private String password;
    private Map<String, Object> settings;
    private Boolean backupDisabled = false;
    @Deprecated
    private List<String> initScriptIdentifiers;
    private String physicalDatabaseId;
}
