package org.qubership.cloud.dbaas.monitoring.annotation;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.*;

@InterceptorBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface TimeMeasure {
    /**
     * Name of the Timer metric.
     *
     * @return name of the Timer metric
     */
    @Nonbinding
    String value() default "dbaas.time.metric";

    /**
     * Must be an even number of arguments representing key/value pairs of tags.
     *
     * @return key-value pair of tags
     * @see io.micrometer.core.instrument.Timer.Builder#tags(String...)
     */
    @Nonbinding
    String[] tags() default {};

    /**
     * Must be a class field names for tags
     *
     * @return list of fields
     * @see io.micrometer.core.instrument.Timer.Builder#tags(String...)
     */
    @Nonbinding
    String[] fieldTags() default {};
}