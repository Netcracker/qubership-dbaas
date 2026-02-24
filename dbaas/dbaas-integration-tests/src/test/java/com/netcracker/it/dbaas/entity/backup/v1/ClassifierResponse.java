package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.SortedMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassifierResponse {
    private ClassifierType type;
    private String previousDatabase;
    private SortedMap<String, Object> classifier;
    private SortedMap<String, Object> classifierBeforeMapper;
}
