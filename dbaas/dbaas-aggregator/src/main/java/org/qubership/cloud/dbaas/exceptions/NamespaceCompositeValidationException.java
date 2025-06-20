package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class NamespaceCompositeValidationException extends ValidationException {

    public NamespaceCompositeValidationException(Source source, String detail) {
        super(ErrorCodes.CORE_DBAAS_7003, ErrorCodes.CORE_DBAAS_7003.getDetail(detail), source);
    }

    public NamespaceCompositeValidationException(Source source, String detail, Integer httpCode) {
        super(ErrorCodes.CORE_DBAAS_7003, ErrorCodes.CORE_DBAAS_7003.getDetail(detail), source);
        super.setStatus(httpCode);
    }
}
