package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;

import java.util.Map;

public class ConnectionPropertiesNotContainRoleException extends ErrorCodeException {
    public ConnectionPropertiesNotContainRoleException(Map<String, Object> classifier) {
        super(ErrorCodes.CORE_DBAAS_4024, ErrorCodes.CORE_DBAAS_4024.getDetail(classifier.toString()));
    }
}