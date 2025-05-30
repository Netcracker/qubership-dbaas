package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class InvalidDbSettingsException extends ValidationException {
    public InvalidDbSettingsException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4038, ErrorCodes.CORE_DBAAS_4038.getDetail(detail), Source.builder().pointer("/settings").build());
    }
}
