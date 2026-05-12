package com.netcracker.cloud.dbaas.utils.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotEmptyFilterValidation.class)
public @interface NotEmptyFilter {
    String message() default "Filter must have at least one non-null field";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
