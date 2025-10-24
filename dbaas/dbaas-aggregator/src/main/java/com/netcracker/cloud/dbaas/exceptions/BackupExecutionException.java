package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import lombok.Getter;

import java.net.URI;

@Getter
public class BackupExecutionException extends ErrorCodeException {
    private final URI location;

    public BackupExecutionException(URI location, String details, Throwable cause) {
        super(ErrorCodes.CORE_DBAAS_4018, ErrorCodes.CORE_DBAAS_4018.getDetail(details), cause);
        this.location = location;
    }

    public BackupExecutionException(String details, Throwable cause) {
        super(ErrorCodes.CORE_DBAAS_4018, ErrorCodes.CORE_DBAAS_4018.getDetail(details), cause);
        this.location = null;
    }
}
