package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
public class RestoreExternalDatabaseResponse {
    private UUID id;
    private String name;
    private String type;
    private List<ClassifierResponse> classifiers;
}

