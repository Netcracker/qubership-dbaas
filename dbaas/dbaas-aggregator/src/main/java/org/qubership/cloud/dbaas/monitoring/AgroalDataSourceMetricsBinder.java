package org.qubership.cloud.dbaas.monitoring;


import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

public class AgroalDataSourceMetricsBinder implements MeterBinder {

    public static final String ACTIVE_COUNT_METRIC_NAME = "agroal.active.count";
    public static final String MAX_USED_COUNT_METRIC_NAME = "agroal.max.used.count";
    public static final String AWAITING_COUNT_METRIC_NAME = "agroal.awaiting.count";
    public static final String ACQUIRE_COUNT_METRIC_NAME = "agroal.acquire.count";
    public static final String CREATION_COUNT_METRIC_NAME = "agroal.creation.count";
    public static final String LEAK_DETECTION_COUNT_METRIC_NAME = "agroal.leak.detection.count";
    public static final String DESTROY_COUNT_METRIC_NAME = "agroal.destroy.count";
    public static final String FLUSH_COUNT_METRIC_NAME = "agroal.flush.count";
    public static final String INVALID_COUNT_METRIC_NAME = "agroal.invalid.count";
    public static final String REAP_COUNT_METRIC_NAME = "agroal.reap.count";
    public static final String BLOCKING_TIME_AVERAGE_METRIC_NAME = "agroal.blocking.time.average";
    public static final String BLOCKING_TIME_MAX_METRIC_NAME = "agroal.blocking.time.max";
    public static final String BLOCKING_TIME_TOTAL_METRIC_NAME = "agroal.blocking.time.total";
    public static final String CREATION_TIME_AVERAGE_METRIC_NAME = "agroal.creation.time.average";
    public static final String CREATION_TIME_MAX_METRIC_NAME = "agroal.creation.time.max";
    public static final String CREATION_TIME_TOTAL_METRIC_NAME = "agroal.creation.time.total";

    public static final String MILLISECONDS_UNIT = "milliseconds";

    private static Function<Supplier<Duration>, Long> convertToMillis = durationSupplier -> durationSupplier.get().toMillis();

    private AgroalDataSource agroalDataSource;
    private final Iterable<Tag> tags;

    public AgroalDataSourceMetricsBinder(AgroalDataSource agroalDataSource, Iterable<Tag> tags) {
        this.agroalDataSource = agroalDataSource;
        this.tags = tags;
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        AgroalDataSourceMetrics metrics = agroalDataSource.getMetrics();

        bindGauge(ACTIVE_COUNT_METRIC_NAME, "Number of active connections. These connections are in use and not available to be acquired.", metrics::activeCount, meterRegistry);
        bindGauge(MAX_USED_COUNT_METRIC_NAME, "Maximum number of connections active simultaneously.", metrics::maxUsedCount, meterRegistry);
        bindGauge(AWAITING_COUNT_METRIC_NAME, "Approximate number of threads blocked, waiting to acquire a connection.", metrics::awaitingCount, meterRegistry);

        bindCounter(ACQUIRE_COUNT_METRIC_NAME, "Number of times an acquire operation succeeded.", metrics::acquireCount, meterRegistry);
        bindCounter(CREATION_COUNT_METRIC_NAME, "Number of created connections.", metrics::creationCount, meterRegistry);
        bindCounter(LEAK_DETECTION_COUNT_METRIC_NAME, "Number of times a leak was detected. A single connection can be detected multiple times.", metrics::leakDetectionCount, meterRegistry);
        bindCounter(DESTROY_COUNT_METRIC_NAME, "Number of destroyed connections.", metrics::destroyCount, meterRegistry);
        bindCounter(FLUSH_COUNT_METRIC_NAME, "Number of connections removed from the pool, not counting invalid / idle.", metrics::flushCount, meterRegistry);
        bindCounter(INVALID_COUNT_METRIC_NAME, "Number of connections removed from the pool for being idle.", metrics::invalidCount, meterRegistry);
        bindCounter(REAP_COUNT_METRIC_NAME, "Number of connections removed from the pool for being idle.", metrics::reapCount, meterRegistry);

        bindGauge(BLOCKING_TIME_AVERAGE_METRIC_NAME, "Average time an application waited to acquire a connection.", MILLISECONDS_UNIT, metrics::blockingTimeAverage, convertToMillis, meterRegistry);
        bindGauge(BLOCKING_TIME_MAX_METRIC_NAME, "Maximum time an application waited to acquire a connection.", MILLISECONDS_UNIT, metrics::blockingTimeMax, convertToMillis, meterRegistry);
        bindGauge(BLOCKING_TIME_TOTAL_METRIC_NAME, "Total time applications waited to acquire a connection.", MILLISECONDS_UNIT, metrics::blockingTimeTotal, convertToMillis, meterRegistry);
        bindGauge(CREATION_TIME_AVERAGE_METRIC_NAME, "Average time for a connection to be created.", MILLISECONDS_UNIT, metrics::creationTimeAverage, convertToMillis, meterRegistry);
        bindGauge(CREATION_TIME_MAX_METRIC_NAME, "Maximum time for a connection to be created.", MILLISECONDS_UNIT, metrics::creationTimeMax, convertToMillis, meterRegistry);
        bindGauge(CREATION_TIME_TOTAL_METRIC_NAME, "Total time waiting for connections to be created.", MILLISECONDS_UNIT, metrics::creationTimeTotal, convertToMillis, meterRegistry);
    }

    private void bindGauge(String name, String description, Supplier<Number> gaugeFunction, MeterRegistry meterRegistry) {
        Gauge.builder(name, gaugeFunction)
                .description(description)
                .tags(tags)
                .register(meterRegistry);
    }

    private <T, R extends Number> void bindGauge(String name, String description, String unit, T obj, Function<T, R> gaugeFunction, MeterRegistry meterRegistry) {
        Gauge.builder(name, obj, x -> gaugeFunction.apply(obj).doubleValue())
                .description(description)
                .baseUnit(unit)
                .tags(tags)
                .register(meterRegistry);
    }

    private void bindCounter(String name, String description, Supplier<Number> countFunction, MeterRegistry meterRegistry) {
        FunctionCounter.builder(name, countFunction, x -> countFunction.get().doubleValue())
                .description(description)
                .tags(tags)
                .register(meterRegistry);
    }
}