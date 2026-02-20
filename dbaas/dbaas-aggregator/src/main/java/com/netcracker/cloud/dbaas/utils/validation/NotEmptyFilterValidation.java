package com.netcracker.cloud.dbaas.utils.validation;

import com.netcracker.cloud.dbaas.dto.backupV2.Filter;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

public class NotEmptyFilterValidation implements ConstraintValidator<NotEmptyFilter, Filter> {
    @Override
    public boolean isValid(Filter value, ConstraintValidatorContext context) {
        return isValid(value.getNamespace()) ||
                isValid(value.getMicroserviceName()) ||
                isValid(value.getDatabaseType()) ||
                isValid(value.getDatabaseKind());
    }

    private boolean isValid(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
