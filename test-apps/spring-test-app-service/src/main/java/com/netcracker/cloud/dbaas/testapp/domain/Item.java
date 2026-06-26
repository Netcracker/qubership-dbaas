package com.netcracker.cloud.dbaas.testapp.domain;

import java.time.Instant;

/**
 * A row in {@code spring_test_app_items}. Mirrors the Go service's item shape so both
 * services satisfy the same integration-test contract ({@code {"name": ...}}).
 */
public record Item(Long id, String name, Instant createdAt) {
}
