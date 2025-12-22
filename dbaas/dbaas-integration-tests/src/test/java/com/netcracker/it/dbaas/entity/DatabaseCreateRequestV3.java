package com.netcracker.it.dbaas.entity;

import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@RequiredArgsConstructor
public class DatabaseCreateRequestV3 {
    @NonNull
    private Map<String, Object> classifier;
    private String dbName;
    @Deprecated
    private List<String> initScriptIdentifiers;
    @NonNull
    private String type;

    private String namePrefix;

    private Boolean backupDisabled = false;

    private Map<String, Object> settings;

    private String physicalDatabaseId;

    private String originService;

    private String userRole;

    public static DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder builder(Map<String, Object> classifier,
                                                                                 String type) {
        return new DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder()
                .classifier(classifier)
                .type(type);
    }
}
