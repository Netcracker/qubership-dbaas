package org.qubership.cloud.dbaas.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class PrometheusMetric {
    String metricName;
    String type;
    Map<String, String> values;
}
