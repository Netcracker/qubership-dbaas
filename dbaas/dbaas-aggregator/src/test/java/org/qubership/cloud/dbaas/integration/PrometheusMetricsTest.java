package org.qubership.cloud.dbaas.integration;

import org.qubership.cloud.dbaas.dto.PrometheusMetric;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class PrometheusMetricsTest {

    private static final String GAUGE = "gauge";
    private static final String COUNTER = "counter";

    @Test
    void test() {
        String contentAsString = given()
                .accept(ContentType.TEXT)
                .get("/prometheus")
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .asString();

        List<PrometheusMetric> metrics = new ArrayList<>();
        String[] metricLines = contentAsString.split("# HELP");
        for (String metric : metricLines) {
            if (metric.isEmpty()) continue;
            PrometheusMetric parsedMetric = parseMetric(metric);
            metrics.add(parsedMetric);
        }

        assertTrue(metrics.stream().anyMatch(this::checkJVMBufferMemoryUsedBytesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMThreadsPeakThreadsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMGCMaxDataSizeBytesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMMemoryUsedBytesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkProcessUptimeSecondsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkProcessStartTimeSecondsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMGCMemoryPromotedBytesTotalMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMThreadsStatesThreadsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMBufferTotalCapacityBytesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMMemoryMaxBytesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMClassesLoadedClassesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMBufferCountBuffersMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDbaasRegistrationDatabasesLostNumberMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMGCLiveDataSizeBytesMetric));

        assertTrue(metrics.stream().anyMatch(this::checkDataSourceIdleConnectionsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceActiveConnectionsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceConnectionsPendingMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceConnectionWaitTimeMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceConnectionsCreationSecondsMaxMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceConnectionsCreationMillisecondsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceConnectionsMaxMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceInvalidCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceAcquireCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceReapCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceBlockingTimeMaxMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceLeakDetectionCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceDestroyCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceFlushCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceBlockingTimeTotalMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceCreationTimeAverageMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDataSourceCreationCountTotalMetric));

        assertTrue(metrics.stream().anyMatch(this::checkJVMGCMemoryAllocatedBytesTotalMetric));
        assertTrue(metrics.stream().anyMatch(this::checkProcessCpuUsageMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDbaasRegistrationDatabasesDeletingNumberMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDbaasRegistrationDatabasesGhostNumberMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDbaasDatabasesTotalNumberMetric));
        assertTrue(metrics.stream().anyMatch(this::checkDbaasRegistrationDatabasesTotalNumberMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMThreadsLiveThreadsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMMemoryCommittedBytesMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMClassesUnloadedClassesTotalMetric));
        assertTrue(metrics.stream().anyMatch(this::checkJVMThreadsDaemonThreadsMetric));
        assertTrue(metrics.stream().anyMatch(this::checkSystemCpuCountMetric));
        assertTrue(metrics.stream().anyMatch(this::checkSystemCpuUsageMetric));
    }

    private boolean checkJVMBufferMemoryUsedBytesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_buffer_memory_used_bytes") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMThreadsPeakThreadsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_threads_peak_threads") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMGCMaxDataSizeBytesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_gc_max_data_size_bytes") && metric.getType().equals(GAUGE);
    }

    private boolean checkProcessUptimeSecondsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("process_uptime_seconds") && metric.getType().equals(GAUGE)
                && metric.getValues().size() == 1;
    }

    private boolean checkProcessStartTimeSecondsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("process_start_time_seconds") && metric.getType().equals(GAUGE)
                && metric.getValues().size() == 1;
    }

    private boolean checkJVMMemoryUsedBytesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_memory_used_bytes") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceIdleConnectionsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_available_count") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceActiveConnectionsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_active_count") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceConnectionsPendingMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_awaiting_count") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceConnectionWaitTimeMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_blocking_time_average_milliseconds") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceConnectionsCreationSecondsMaxMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_creation_time_max_milliseconds") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceConnectionsCreationMillisecondsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_creation_time_total_milliseconds") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceConnectionsMaxMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_max_used_count") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceInvalidCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_invalid_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkDataSourceAcquireCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_acquire_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkDataSourceReapCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_reap_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkDataSourceBlockingTimeMaxMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_blocking_time_max_milliseconds") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceLeakDetectionCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_leak_detection_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkDataSourceDestroyCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_destroy_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkDataSourceFlushCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_flush_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkDataSourceBlockingTimeTotalMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_blocking_time_total_milliseconds") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceCreationTimeAverageMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_creation_time_average_milliseconds") && metric.getType().equals(GAUGE);
    }

    private boolean checkDataSourceCreationCountTotalMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("agroal_creation_count_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkJVMGCMemoryPromotedBytesTotalMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_gc_memory_promoted_bytes_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkJVMThreadsStatesThreadsMetric(PrometheusMetric metric) {
        int threadStatesAmount = 6;
        return metric.getMetricName().equals("jvm_threads_states_threads") && metric.getType().equals(GAUGE)
                && metric.getValues().size() == threadStatesAmount;
    }

    private boolean checkJVMBufferTotalCapacityBytesMetric(PrometheusMetric metric) {
        int bufferTypesAmount = 3;
        return metric.getMetricName().equals("jvm_buffer_total_capacity_bytes") && metric.getType().equals(GAUGE)
                && metric.getValues().size() == bufferTypesAmount;
    }

    private boolean checkJVMMemoryMaxBytesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_memory_max_bytes") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMClassesLoadedClassesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_classes_loaded_classes") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMBufferCountBuffersMetric(PrometheusMetric metric) {
        int bufferTypesAmount = 3;
        return metric.getMetricName().equals("jvm_buffer_count_buffers") && metric.getType().equals(GAUGE)
                && metric.getValues().size() == bufferTypesAmount;
    }

    private boolean checkDbaasRegistrationDatabasesLostNumberMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("dbaas_registration_databases_lost_number") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMGCLiveDataSizeBytesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_gc_live_data_size_bytes") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMGCMemoryAllocatedBytesTotalMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_gc_memory_allocated_bytes_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkProcessCpuUsageMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("process_cpu_usage") && metric.getType().equals(GAUGE);
    }

    private boolean checkDbaasRegistrationDatabasesDeletingNumberMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("dbaas_registration_databases_deleting_number") && metric.getType().equals(GAUGE);
    }

    private boolean checkDbaasRegistrationDatabasesGhostNumberMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("dbaas_registration_databases_ghost_number") && metric.getType().equals(GAUGE);
    }

    private boolean checkDbaasRegistrationDatabasesTotalNumberMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("dbaas_registration_databases_total_number") && metric.getType().equals(GAUGE);
    }

    private boolean checkDbaasDatabasesTotalNumberMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("dbaas_databases_total_number") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMThreadsLiveThreadsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_threads_live_threads") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMMemoryCommittedBytesMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_memory_committed_bytes") && metric.getType().equals(GAUGE);
    }

    private boolean checkJVMClassesUnloadedClassesTotalMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_classes_unloaded_classes_total") && metric.getType().equals(COUNTER);
    }

    private boolean checkJVMThreadsDaemonThreadsMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("jvm_threads_daemon_threads") && metric.getType().equals(GAUGE);
    }

    private boolean checkSystemCpuCountMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("system_cpu_count") && metric.getType().equals(GAUGE);
    }

    private boolean checkSystemCpuUsageMetric(PrometheusMetric metric) {
        return metric.getMetricName().equals("system_cpu_usage") && metric.getType().equals(GAUGE);
    }

    private PrometheusMetric parseMetric(String lines) {
        PrometheusMetric metric = new PrometheusMetric();
        List<String> linesList = Arrays.stream(lines.split("\n")).map(String::trim).collect(Collectors.toList());
        String metricName = parseMetricName(linesList.get(0));
        metric.setMetricName(metricName);
        metric.setType(parseMetricType(linesList.get(1), metricName));
        Map<String, String> metricsValues = fillMetricsValues(linesList);
        metric.setValues(metricsValues);
        return metric;
    }

    private Map<String, String> fillMetricsValues(List<String> linesList) {
        Map<String, String> metricsValues = new HashMap<>();
        for (int i = 2; i < linesList.size(); i++) {
            String key = linesList.get(i).substring(0, linesList.get(i).lastIndexOf(" ")).trim();
            String value = linesList.get(i).substring(linesList.get(i).lastIndexOf(" ")).trim();
            metricsValues.put(key, value);
        }
        return metricsValues;
    }

    private String parseMetricType(String typeString, String metricName) {
        return typeString.replace("# TYPE", "").replace(metricName, "").trim();
    }

    private String parseMetricName(String helpString) {
        String[] helpStringSplittedArray = helpString.trim().split(" ");
        return helpStringSplittedArray.length == 1 ? helpString : helpStringSplittedArray[0];
    }
}