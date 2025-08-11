package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.checkerframework.checker.units.qual.A;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@AllArgsConstructor
@Schema(description = "Response containing the restore operation details")
public class RestoreResponse {
    @Schema(description = "Unique identifier of the restore operation", example = "restore-before-prod-update-20251203T1020-4t6S", required = true)
    private String restoreName;
}
