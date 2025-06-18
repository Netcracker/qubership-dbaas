package org.qubership.cloud.dbaas.monitoring.interceptor;

import org.qubership.cloud.dbaas.monitoring.annotation.TimeMeasure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Slf4j
public class TimeMeasurementManager {
    private final String SUCCESS_RESULT = "SUCCESS";
    private final String FAIL_RESULT = "FAIL";

    @Inject
    private MeterRegistry meterRegistry;

    public Object measureTime(InvocationContext context) throws Throwable {
        TimeMeasure timeMeasureAnnotation = getAnnotation(context);
        String metricName = timeMeasureAnnotation.value();
        Tags tags = getTags(context.getTarget(), timeMeasureAnnotation);
        Object value;
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            value = context.proceed();
            increaseSuccessRequestCounter(metricName, tags);
        } catch (Throwable throwable) {
            increaseFailRequestCounter(metricName, tags);
            throw throwable;
        } finally {
            Timer timer = meterRegistry.timer(metricName, tags);
            sample.stop(timer);
        }
        return value;
    }

    private void increaseFailRequestCounter(String metricName, Tags tags) {
        increaseRequestCounter(metricName, FAIL_RESULT, tags);
    }

    private void increaseSuccessRequestCounter(String metricName, Tags tags) {
        increaseRequestCounter(metricName, SUCCESS_RESULT, tags);
    }

    private Tags getTags(Object targetObject, TimeMeasure timeMeasureAnnotation) throws IllegalAccessException {
        List<String> listOfTagsFromClassFields = getTagsFromClassFields(targetObject, timeMeasureAnnotation);
        return Tags
                .of(listOfTagsFromClassFields.toArray(new String[listOfTagsFromClassFields.size()]))
                .and(timeMeasureAnnotation.tags());
    }

    private List<String> getTagsFromClassFields(Object targetObject, TimeMeasure timeMeasureAnnotation) throws IllegalAccessException {
        List<String> listOfTags = new ArrayList<>();

        for (String classField : timeMeasureAnnotation.fieldTags()) {
            String fieldValue = FieldUtils.readField(targetObject, classField, true).toString();
            listOfTags.add(classField);
            listOfTags.add(fieldValue);
        }
        return listOfTags;
    }

    private TimeMeasure getAnnotation(InvocationContext context) {
        Method method = context.getMethod();
        return method.getAnnotation(TimeMeasure.class);
    }

    private void increaseRequestCounter(String metricName, String result, Tags metricTags) {
        Counter.builder(metricName + ".request.total").tags(metricTags).tag("result", result).register(meterRegistry).increment();
    }

    public InvocationHandler provideTimeMeasurementInvocationHandler(Object object) {
        return (proxy, method, args) -> {
            method = object.getClass().getMethod(method.getName(), method.getParameterTypes());
            TimeMeasure timeMeasureAnnotation = MethodUtils.getAnnotation(method, TimeMeasure.class, true, true);
            Object returnValue;
            if (timeMeasureAnnotation != null) {
                String metricName = timeMeasureAnnotation.value();
                Tags tags = getTags(object, timeMeasureAnnotation);
                Timer.Sample sample = Timer.start(meterRegistry);
                try {
                    returnValue = method.invoke(object, args);
                    increaseSuccessRequestCounter(metricName, tags);
                } catch (Throwable throwable) {
                    increaseFailRequestCounter(metricName, tags);
                    throw throwable;
                } finally {
                    Timer timer = meterRegistry.timer(metricName, tags);
                    sample.stop(timer);
                }
            } else {
                returnValue = method.invoke(object, args);
            }
            return returnValue;
        };
    }
}
