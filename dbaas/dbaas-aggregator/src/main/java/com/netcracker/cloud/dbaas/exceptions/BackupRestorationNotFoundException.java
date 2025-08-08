package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

import java.util.UUID;

@Getter
public class BackupRestorationNotFoundException extends ValidationException {

    public BackupRestorationNotFoundException(UUID restorationId, Source source) {
        super(ErrorCodes.CORE_DBAAS_4015, ErrorCodes.CORE_DBAAS_4015.getDetail(String.valueOf(restorationId)), source);
    }
}
