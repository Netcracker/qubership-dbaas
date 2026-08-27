package com.netcracker.cloud.dbaas.testapp.quarkus;

import java.time.Instant;

/**
 * A row in {@code quarkus_test_app_items}. Mirrors the Go/Spring item shape so all three services
 * satisfy the same integration-test contract ({@code {"name": ...}}).
 */
public record Item(Long id, String name, Instant createdAt) {
}
