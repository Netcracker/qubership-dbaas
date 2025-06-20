package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

@Getter
public class TrackingIdNotFoundException extends ValidationException {
    public TrackingIdNotFoundException(String message) {
        super(ErrorCodes.CORE_DBAAS_7002, ErrorCodes.CORE_DBAAS_7002.getDetail(message), Source.builder().build());
    }
}
