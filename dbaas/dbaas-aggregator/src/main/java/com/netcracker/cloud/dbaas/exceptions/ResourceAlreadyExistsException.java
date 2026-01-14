package com.netcracker.cloud.dbaas.exceptions;

import com.netcracker.cloud.dbaas.dto.Source;

public class ResourceAlreadyExistsException extends ValidationException {
    public ResourceAlreadyExistsException(String resourceName, Source source) {
        super(ErrorCodes.CORE_DBAAS_4046, ErrorCodes.CORE_DBAAS_4046.getDetail(resourceName), source);
    }
}
