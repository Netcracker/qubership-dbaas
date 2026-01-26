package com.netcracker.it.dbaas.entity.config;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class DeclarativePayload {
    String apiVersion = "core.netcracker.com/v1";
    String kind = "DBaaS";
    String subKind;
    String declarationVersion;
    Metadata metadata;
    DeclarativeConfig spec;

    public DeclarativePayload(String serviceName, String namespace, String declarationName, String subKind, DeclarativeConfig spec) {
        this.subKind = subKind;
        this.metadata = new Metadata(declarationName, namespace, serviceName);
        this.spec = spec;
    }

    @Data
    @AllArgsConstructor
    public static class Metadata {
        String name;
        String namespace;
        String microserviceName;
    }
}
