package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import jakarta.ws.rs.core.Response;

public class BgRequestValidationException extends ValidationException {

    public BgRequestValidationException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4037, detail, Source.builder().build());
        super.setStatus(Response.Status.CONFLICT.getStatusCode());
    }
}