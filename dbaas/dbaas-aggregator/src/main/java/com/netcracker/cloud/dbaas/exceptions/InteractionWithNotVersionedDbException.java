package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4041;

public class InteractionWithNotVersionedDbException extends ErrorCodeException {
    public InteractionWithNotVersionedDbException(String detail) {
        super(CORE_DBAAS_4041, detail);
    }
}
