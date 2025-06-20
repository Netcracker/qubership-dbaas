package org.qubership.cloud.dbaas.monitoring.indicators;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
@Getter
@Builder
public class HealthCheckResponse {
    @JsonIgnore
    private final String name;
    private final HealthStatus status;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> details;

    public static class HealthCheckResponseBuilder {

        public HealthCheckResponseBuilder details(String key, Object value) {
            if (details == null) {
                this.details = new HashMap<>();
            }
            this.details.put(key, value);
            return this;
        }

        public HealthCheckResponseBuilder up() {
            this.status = HealthStatus.UP;
            return this;
        }

        public HealthCheckResponseBuilder down() {
            this.status = HealthStatus.DOWN;
            return this;
        }

        public HealthCheckResponseBuilder problem() {
            this.status = HealthStatus.PROBLEM;
            return this;
        }
    }
}
