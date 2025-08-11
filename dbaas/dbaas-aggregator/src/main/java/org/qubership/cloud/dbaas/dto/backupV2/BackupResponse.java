package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Response containing backup operation details")
public class BackupResponse {
    @Schema(description = "Unique identifier of the backup", example = "before-prod-update-20251013T1345-G5s8", required = true)
    private String backupName;
}
