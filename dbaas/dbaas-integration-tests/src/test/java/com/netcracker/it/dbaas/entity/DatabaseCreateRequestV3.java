package com.netcracker.it.dbaas.entity;

import lombok.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.netcracker.it.dbaas.test.AbstractIT.OPENSEARCH_TYPE;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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

    public DatabaseCreateRequestV3(@NonNull Map<String, Object> classifier, @NonNull String type) {
        this.classifier = classifier;
        this.type = type;
        this.settings = defaultSettings(type);
    }

    public static DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder builder(Map<String, Object> classifier,
                                                                                 String type) {
        return new DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder()
                .classifier(classifier)
                .type(type)
                .settings(defaultSettings(type));
    }

    private static Map<String, Object> defaultSettings(String type) {
        if (OPENSEARCH_TYPE.equals(type)) {
            return Collections.singletonMap("resourcePrefix", true);
        }
        return null;
    }
}
