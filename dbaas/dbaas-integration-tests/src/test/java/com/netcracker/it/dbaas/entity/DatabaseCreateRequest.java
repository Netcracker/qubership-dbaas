package com.netcracker.it.dbaas.entity;

import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@Builder
@RequiredArgsConstructor
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseCreateRequest {
    @NonNull
    private Map<String, Object> classifier;
    @NonNull
    private String type;
    private String namePrefix;
    private String dbName;
    private String username;
    private String password;
    @Deprecated
    private List<String> initScriptIdentifiers;
    private Map<String, Object> settings;
    private Boolean backupDisabled = false;
    private String dbOwner;

    private String physicalDatabaseId;

    public static DatabaseCreateRequest.DatabaseCreateRequestBuilder builder(Map<String, Object> classifier,
                                                                                 String type,
                                                                                 String dbOwner) {
        return new DatabaseCreateRequest.DatabaseCreateRequestBuilder()
                .classifier(classifier)
                .type(type)
                .dbOwner(dbOwner);
    }

}
