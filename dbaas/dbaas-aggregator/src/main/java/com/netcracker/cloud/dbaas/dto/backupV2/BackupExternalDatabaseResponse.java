package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.SortedMap;

@Data
@NoArgsConstructor
@Schema(description = "External database details")
public class BackupExternalDatabaseResponse {

    private String name;
    private String type;
    private List<SortedMap<String, Object>> classifiers;
}
