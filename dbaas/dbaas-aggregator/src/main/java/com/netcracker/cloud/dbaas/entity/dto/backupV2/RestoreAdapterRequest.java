package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import java.util.List;
import java.util.Map;

public record RestoreAdapterRequest(String storageName,
                                    String blobPath,
                                    List<Map<String, String>> databases) {
}
