package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@AllArgsConstructor
@Schema(description = "Response containing backup operation details")
public class BackupOperationResponse {
    @Schema(description = "Unique identifier of the backup", required = true)
    private String backupName;
}
