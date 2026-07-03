package com.netcracker.cloud.dbaas.exceptions;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public class FailedNamespaceIsolationCheckException extends ForbiddenException {
    public FailedNamespaceIsolationCheckException(String namespaceFromPath, String namespaceFromToken) {
        super(ErrorCodes.CORE_DBAAS_4055, ErrorCodes.CORE_DBAAS_4055.getDetail(namespaceFromPath, namespaceFromToken));
    }
}
