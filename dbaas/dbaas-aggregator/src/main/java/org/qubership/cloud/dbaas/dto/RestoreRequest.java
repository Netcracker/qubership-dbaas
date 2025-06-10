package org.qubership.cloud.dbaas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

@Data
public class RestoreRequest {
    private List<Database> databases = new ArrayList<>();
    private Boolean regenerateNames = false;

    @Data
    @AllArgsConstructor
    public static class Database {
        @NonNull
        private String namespace;
        @NonNull
        private String microservice;
        @NonNull
        private String name;
        private String prefix;

        public Database(@NonNull String namespace, @NonNull String microservice, @NonNull String name) {
            this.namespace = namespace;
            this.microservice = microservice;
            this.name = name;
        }
    }
}
