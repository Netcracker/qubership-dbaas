package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import jakarta.ws.rs.core.Response;

import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4040;

public class BgNamespaceNotEmptyException extends ValidationException {
    public BgNamespaceNotEmptyException(String detail) {
        super(CORE_DBAAS_4040, ErrorCodes.CORE_DBAAS_4040.getDetail(detail), Source.builder().build());
        super.setStatus(Response.Status.CONFLICT.getStatusCode());
    }
}
