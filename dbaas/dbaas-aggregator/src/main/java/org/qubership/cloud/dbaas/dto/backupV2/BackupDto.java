package org.qubership.cloud.dbaas.dto.backupV2;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
public class BackupDto {

    @NotNull
    String namespace;

    @NotNull
    String backupName;
}
