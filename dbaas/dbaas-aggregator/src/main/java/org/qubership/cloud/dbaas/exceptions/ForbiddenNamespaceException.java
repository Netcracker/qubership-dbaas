package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class ForbiddenNamespaceException extends ErrorCodeException {
    private final Source source;
    public ForbiddenNamespaceException(String requestNs, String classifierNs, Source source) {
        super(ErrorCodes.CORE_DBAAS_4004, ErrorCodes.CORE_DBAAS_4004.getDetail(requestNs, classifierNs, ""));
        this.source = source;
    }

    public ForbiddenNamespaceException(String requestNs, String classifierNs, String detail, Source source) {
        super(ErrorCodes.CORE_DBAAS_4004, ErrorCodes.CORE_DBAAS_4004.getDetail(requestNs, classifierNs, detail));
        this.source = source;
    }
}
