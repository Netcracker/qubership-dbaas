package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Backup metadata to be stored")
public class BackupMetadataRequest {
    @Schema(description = "Backup metadata", required = true)
    private BackupResponse metadata;
    @Schema(description = "Control sum of the metadata", required = true)
    private String controlSum;

}
