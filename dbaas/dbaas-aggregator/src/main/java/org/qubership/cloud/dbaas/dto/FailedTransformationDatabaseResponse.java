package org.qubership.cloud.dbaas.dto;

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
