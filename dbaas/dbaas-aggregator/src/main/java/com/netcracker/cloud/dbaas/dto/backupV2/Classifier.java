package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.SortedMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Classifier {
    private ClassifierType type;
    private SortedMap<String, Object> classifier;
    private SortedMap<String, Object> classifierBeforeMapper;
}
