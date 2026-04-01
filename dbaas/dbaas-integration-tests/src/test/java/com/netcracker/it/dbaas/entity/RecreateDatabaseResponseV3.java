package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class RecreateDatabaseResponseV3 {
    private List<Recreated> successfully = new ArrayList<>();
    private List<NotRecreated> unsuccessfully = new ArrayList<>();

    public void dbRecreated(Map<String, Object> classifier, String type, DatabaseResponse newDb) {
        successfully.add(new Recreated(classifier, type, newDb));
    }

    public void dbNotRecreated(Map<String, Object> classifier, String type, String error) {
        unsuccessfully.add(new NotRecreated(type, classifier, error));
    }

    @Data
    @NoArgsConstructor
    @RequiredArgsConstructor
    public static class Recreated {
        @NonNull
        private Map<String, Object> classifier;
        @NonNull
        private String type;
        @NonNull
        private DatabaseResponse newDb;
    }

    @Data
    @NoArgsConstructor
    @RequiredArgsConstructor
    public static class NotRecreated {
        @NonNull
        private String type;
        @NonNull
        private Map<String, Object> classifier;
        @NonNull
        private String error;
    }
}
