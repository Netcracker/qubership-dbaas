package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class InvalidUpdateConnectionPropertiesRequestException extends ValidationException {
    public InvalidUpdateConnectionPropertiesRequestException(String detail, Source source) {
        super(ErrorCodes.CORE_DBAAS_4020, ErrorCodes.CORE_DBAAS_4020.getDetail(detail), source);
    }
}
