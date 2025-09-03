package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;

import static com.netcracker.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4041;

public class NoDatabaseInActiveNamespaceException extends ErrorCodeException {
    public NoDatabaseInActiveNamespaceException(String detail) {
        super(CORE_DBAAS_4041, detail);
    }
}
