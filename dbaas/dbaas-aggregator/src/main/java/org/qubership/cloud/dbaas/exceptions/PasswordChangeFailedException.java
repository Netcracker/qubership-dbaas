package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.dto.PasswordChangeResponse;
import lombok.Getter;

@Getter
public class PasswordChangeFailedException extends ErrorCodeException {

    private final PasswordChangeResponse response;
    private final int status;

    public PasswordChangeFailedException(PasswordChangeResponse response, int status) {
        super(ErrorCodes.CORE_DBAAS_4009, ErrorCodes.CORE_DBAAS_4009.getDetail());
        this.response = response;
        this.status = status;
    }
}
