package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class DBBackupValidationException extends ValidationException {

    public DBBackupValidationException(Source source, String detail) {
        super(ErrorCodes.CORE_DBAAS_4001, ErrorCodes.CORE_DBAAS_4001.getDetail(detail), source);
    }
    public DBBackupValidationException(Source source, String detail, int status) {
        super(ErrorCodes.CORE_DBAAS_4001, ErrorCodes.CORE_DBAAS_4001.getDetail(detail), source);
        super.setStatus(status);
    }

}
