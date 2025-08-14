package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@AllArgsConstructor
@Schema(description = "Logical backup details")
public class LogicalBackupDto {

    @Schema(description = "Unique identifier of the adapter", example = "4e1e9922-e5bf-490d-bb57-18a14de593c6", required = true)
    private String adapterId;
    @Schema(description = "Type of the adapter", required = true)
    private String type;
    @Schema(description = "Status of the logical backup", required = true)
    private LogicalBackupStatusDto status;
    @Schema(
            description = "List of logical backup databases",
            required = true
    )
    private List<BackupDatabaseDto> backupDatabases;
}
