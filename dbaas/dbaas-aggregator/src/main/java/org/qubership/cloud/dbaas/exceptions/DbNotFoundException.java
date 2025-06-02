package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

import java.util.Map;
import java.util.Optional;

@Getter
public class DbNotFoundException extends ValidationException {

    public DbNotFoundException(String type, Map<String, Object> classifier, Source source) {
        super(ErrorCodes.CORE_DBAAS_4006, ErrorCodes.CORE_DBAAS_4006.getDetail(type,
                Optional.ofNullable(classifier).map(Map::toString).orElse("null")), source);
    }

    public DbNotFoundException(String message, Source source) {
        super(ErrorCodes.CORE_DBAAS_4029, ErrorCodes.CORE_DBAAS_4029.getDetail(message), source);
    }
}
