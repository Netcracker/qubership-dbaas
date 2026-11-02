package com.netcracker.cloud.dbaas.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "Health status of the adapter")
public class AdapterHealthStatus {
    public final static String HEALTH_CHECK_STATUS_PROBLEM = "PROBLEM";
    public final static String HEALTH_CHECK_STATUS_UNKNOWN = "UNKNOWN";
    public final static String HEALTH_CHECK_STATUS_UP = "UP";
    @Schema(description = "Health status: UP, UNKNOWN or PROBLEM")
    String status;
}
