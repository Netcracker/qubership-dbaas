package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCode;
import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import com.netcracker.cloud.dbaas.dto.Source;
import lombok.Getter;
import lombok.Setter;

@Getter
public abstract class ValidationException extends ErrorCodeException {
    @Getter
    @Setter
    private Integer status;
    private final Source source;

    protected ValidationException(ErrorCode errorCode, String detail, Source source) {
        super(errorCode, detail);
        this.source = source;
    }
}
