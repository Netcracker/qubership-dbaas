package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class InvalidLinkDatabasesRequestException extends ValidationException {
    public InvalidLinkDatabasesRequestException(String detail, Source source) {
        super(ErrorCodes.CORE_DBAAS_4044, ErrorCodes.CORE_DBAAS_4044.getDetail(detail), source);
    }
}
