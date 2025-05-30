package org.qubership.cloud.dbaas.dto.declarative;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.conigs.DeclarativeConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

@Data
@RegisterForReflection
public class DeclarativePayload {
    @JsonProperty(required = true)
    String apiVersion;

    @JsonProperty(required = true)
    String kind;

    @JsonProperty(required = true)
    String subKind;

    String declarationVersion;

    @JsonProperty(required = true)
    Metadata metadata;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY) // Will be parsed manually for better error handling
    DeclarativeConfig spec;

    @Data
    public static class Metadata {
        @JsonProperty(required = true)
        String name;
        @JsonProperty(required = true)
        String namespace;
        @JsonProperty(required = true)
        String microserviceName;
    }
}
