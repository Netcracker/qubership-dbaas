package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.dto.RecreateDatabaseResponse;
import lombok.Getter;

@Getter
public class RecreateDbFailedException extends ErrorCodeException {

    private final RecreateDatabaseResponse result;

    public RecreateDbFailedException(String namespace, RecreateDatabaseResponse result) {
        super(ErrorCodes.CORE_DBAAS_4019, ErrorCodes.CORE_DBAAS_4019.getDetail(namespace));
        this.result = result;
    }
}
