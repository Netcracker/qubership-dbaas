package org.qubership.cloud.dbaas.monitoring.interceptor;

import org.qubership.cloud.dbaas.monitoring.annotation.TimeMeasure;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

@TimeMeasure
@Interceptor
@Priority(0)
public class TimeMeasurementInterceptor {

    @Inject
    private TimeMeasurementManager augmenter;

    @AroundInvoke
    public Object measureTime(InvocationContext context) throws Throwable {
        return augmenter.measureTime(context);
    }
}
