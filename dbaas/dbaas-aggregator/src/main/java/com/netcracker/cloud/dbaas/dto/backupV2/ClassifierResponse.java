package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.ClassifierType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.SortedMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassifierResponse {
    private ClassifierType type;
    private SortedMap<String, Object> classifier;
    private SortedMap<String, Object> classifierBeforeMapper;
}
