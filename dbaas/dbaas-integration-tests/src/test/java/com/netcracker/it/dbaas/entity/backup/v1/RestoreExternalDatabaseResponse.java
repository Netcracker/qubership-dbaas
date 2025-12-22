package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.SortedMap;

@Data
@NoArgsConstructor
public class RestoreExternalDatabaseResponse {
    private String name;
    private String type;
    private List<SortedMap<String, Object>> classifiers;
}

