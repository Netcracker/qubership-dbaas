package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

import java.util.Map;

@Getter
public class InvalidClassifierException extends ValidationException {
    public InvalidClassifierException(String detail, Map<String, Object> classifier, Source source) {
        super(ErrorCodes.CORE_DBAAS_4010, ErrorCodes.CORE_DBAAS_4010.getDetail(detail, classifier), source);
    }
}
