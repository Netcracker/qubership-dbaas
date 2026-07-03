package com.netcracker.cloud.dbaas.dto;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.SortedMap;
import java.util.UUID;

@Data
@AllArgsConstructor
public class FailedTransformationDatabaseResponse {
    private UUID id;
    private SortedMap<String, Object> classifier;
    private String type;
}
