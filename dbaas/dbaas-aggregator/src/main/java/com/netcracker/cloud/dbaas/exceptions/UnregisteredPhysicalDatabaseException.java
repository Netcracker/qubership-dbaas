package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;

public class UnregisteredPhysicalDatabaseException extends ValidationException {
    public UnregisteredPhysicalDatabaseException(String detail) {
        super(ErrorCodes.CORE_DBAAS_4000, ErrorCodes.CORE_DBAAS_4000.getDetail(detail), null);
    }

    public UnregisteredPhysicalDatabaseException(Source source, String detail) {
        super(ErrorCodes.CORE_DBAAS_4000, ErrorCodes.CORE_DBAAS_4000.getDetail(detail), source);
    }
}
