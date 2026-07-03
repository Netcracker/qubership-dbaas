package com.netcracker.cloud.dbaas.entity.dto.backupV2;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import java.util.List;
import java.util.Map;

public record BackupAdapterRequest(String storageName,
                                   String blobPath,
                                   List<Map<String, String>> databases) {
}
