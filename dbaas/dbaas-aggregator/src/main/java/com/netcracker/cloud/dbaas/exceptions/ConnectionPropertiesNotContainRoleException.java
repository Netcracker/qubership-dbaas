package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

import java.util.Map;

public class ConnectionPropertiesNotContainRoleException extends ValidationException {
    public ConnectionPropertiesNotContainRoleException(Map<String, Object> classifier) {
        super(ErrorCodes.CORE_DBAAS_4024, ErrorCodes.CORE_DBAAS_4024.getDetail(classifier.toString()), Source.builder().build());
    }
}
