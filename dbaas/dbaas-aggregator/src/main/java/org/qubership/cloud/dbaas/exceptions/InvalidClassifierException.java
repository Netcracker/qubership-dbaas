package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.dbaas.dto.Source;
import lombok.Getter;

import java.util.Map;

@Getter
public class InvalidClassifierException extends ValidationException {
    public InvalidClassifierException(String detail, Map<String, Object> classifier, Source source) {
        super(ErrorCodes.CORE_DBAAS_4010, ErrorCodes.CORE_DBAAS_4010.getDetail(detail, classifier), source);
    }

    public static InvalidClassifierException withDefaultMsg(Map<String, Object> classifier) {
        return new InvalidClassifierException("Classifier doesn't contain all mandatory fields. " +
                "Check that classifier has `microserviceName`, `scope`. If `scope` = `tenant`, classifier must contain `tenantId` property",
                classifier, Source.builder().pointer("/classifier").build());
    }
}
