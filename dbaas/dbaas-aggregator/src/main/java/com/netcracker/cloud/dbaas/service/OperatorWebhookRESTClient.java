package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;
import com.netcracker.cloud.dbaas.rest.OperatorWebhookRestClient;

public class OperatorWebhookRESTClient implements OperatorWebhook {
    private final OperatorWebhookRestClient restClient;

    public OperatorWebhookRESTClient(OperatorWebhookRestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void notify(RotationEventPayload eventPayload) {
        restClient.notify(eventPayload);
    }
}
