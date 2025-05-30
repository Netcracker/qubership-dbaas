package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.conigs.DeclarativeConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.SortedMap;

@Data
@RegisterForReflection
public class DatabaseDeclaration implements DeclarativeConfig {

    @JsonProperty(value = "classifierConfig", required = true)
    private ClassifierConfig classifierConfig;

    @JsonProperty("lazy")
    private Boolean lazy = false;

    @JsonProperty(value = "type", required = true)
    private String type;

    @JsonProperty("settings")
    private Map<String, Object> settings;

    @JsonProperty("namePrefix")
    private String namePrefix;

    @JsonProperty("versioningConfig")
    private VersioningConfig versioningConfig;

    @JsonProperty(value = "initialInstantiation")
    private InitialInstantiation initialInstantiation;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassifierConfig {
        @JsonProperty("classifier")
        private SortedMap<String, Object> classifier;
    }

    @Data
    public static class VersioningConfig {
        @JsonProperty(value = "approach")
        private String approach = "clone";

    }

    @Data
    public static class InitialInstantiation {
        @JsonProperty(value = "approach")
        private String approach = "clone";

        @JsonProperty("sourceClassifier")
        private SortedMap<String, Object> sourceClassifier;
    }
}