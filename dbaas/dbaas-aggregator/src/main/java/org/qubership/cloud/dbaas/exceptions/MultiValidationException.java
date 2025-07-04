package org.qubership.cloud.dbaas.exceptions;

import org.qubership.cloud.core.error.runtime.MultiCauseException;

import java.util.List;
import java.util.stream.Collectors;

public class MultiValidationException extends MultiCauseException {
    public MultiValidationException(List<? extends ValidationException> causeExceptions) {
        super(causeExceptions);
    }

    public List<ValidationException> getValidationExceptions() {
        return super.getCauseExceptions().stream().map(e -> (ValidationException)e).collect(Collectors.toList());
    }
}
