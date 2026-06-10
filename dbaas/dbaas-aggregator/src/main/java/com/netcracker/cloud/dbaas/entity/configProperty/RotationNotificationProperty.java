package com.netcracker.cloud.dbaas.entity.configProperty;


import io.smallrye.config.ConfigMapping;

import java.time.Duration;

@ConfigMapping(prefix = "dbaas.operator.notification")
public interface RotationNotificationProperty {
    boolean enabled();
    String callbackUrl();
    String jwtAudience();
    Duration retryDelay();
    int maxAttempts();
    Duration sentTtl();
    Duration failedTtl();
    Duration connectTimeout();
    Duration readTimeout();
}
