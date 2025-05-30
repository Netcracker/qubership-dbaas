package org.qubership.cloud.dbaas.monitoring.indicators;

import org.qubership.cloud.dbaas.monitoring.indicators.HealthCheckResponse.HealthCheckResponseBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
@Slf4j
public class AdaptersAccessIndicator implements HealthCheck {

    public static final String ADAPTERS_HEALTH_CHECK_NAME = "adaptersAccessIndicator";

    @Getter
    private volatile AtomicReference<HealthCheckResponse> status = new AtomicReference<>(HealthCheckResponse.builder().name(ADAPTERS_HEALTH_CHECK_NAME).up().build());

    @Override
    public HealthCheckResponse check() {
        HealthCheckResponse st = status.get();
        HealthCheckResponseBuilder builder = HealthCheckResponse.builder().name(ADAPTERS_HEALTH_CHECK_NAME).status(st.getStatus());
        Map<String, Object> statusData = st.getDetails();
        if (statusData != null) {
            statusData.forEach((s, o) -> builder.details(s, String.valueOf(o)));
        }
        return builder.build();
    }
}
