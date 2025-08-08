package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;

public class DeclarativeConfigurationValidationException extends ValidationException {
    public DeclarativeConfigurationValidationException(String message, Source source) {
        super(ErrorCodes.CORE_DBAAS_4035, ErrorCodes.CORE_DBAAS_4035.getDetail(message), source);
    }

    public DeclarativeConfigurationValidationException(String message) {
        super(ErrorCodes.CORE_DBAAS_4036, ErrorCodes.CORE_DBAAS_4036.getDetail(message), Source.builder().build());
    }
}
