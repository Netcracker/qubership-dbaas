package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class IllegalEntityStateException extends ValidationException {
    public IllegalEntityStateException(String reason, Source source) {
        super(ErrorCodes.CORE_DBAAS_4048, ErrorCodes.CORE_DBAAS_4048.getDetail(reason), source);
    }
}
