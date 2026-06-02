package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;

public interface OperatorWebhook {
    void notify(RotationEventPayload eventPayload);
}
