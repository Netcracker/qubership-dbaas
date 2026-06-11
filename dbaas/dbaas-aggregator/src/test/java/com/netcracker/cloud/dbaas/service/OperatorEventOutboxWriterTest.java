package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.enums.OperatorEventType;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.monitoring.OperatorEventMetrics;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.OperatorEventRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
class OperatorEventOutboxWriterTest {

    @Inject
    OperatorEventOutboxWriter operatorEventOutboxWriter;

    @Inject
    OperatorEventRepository operatorEventRepository;

    @InjectMock
    OperatorEventMetrics operatorEventMetrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        operatorEventRepository.deleteAll();
    }

    @Test
    void enqueue_persistsPayload() {
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", "test-ns");
        classifier.put("microserviceName", "svc");

        QuarkusTransaction.requiringNew().run(() ->
                operatorEventOutboxWriter.enqueue(OperatorEventType.ROTATION_OCCURRED, classifier, "postgresql")
        );

        assertEquals(1, operatorEventRepository.count());
    }

    @Test
    void findPreviousOccurredAt_returnsNullWhenNoRows() throws Exception {
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", "test-ns");
        classifier.put("microserviceName", "svc");
        String classifierJson = objectMapper.writeValueAsString(classifier);

        OffsetDateTime result = operatorEventRepository.findPreviousOccurredAt(classifierJson, "postgresql");

        assertNull(result);
    }

    /**
     * Regression test for: occurredAt stored as epoch float causes ::timestamptz cast to fail.
     * Without the @JsonFormat fix this test throws DataException.
     */
    @Test
    void findPreviousOccurredAt_returnsTimestampAfterEnqueue() throws Exception {
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", "test-ns");
        classifier.put("microserviceName", "svc");
        String classifierJson = objectMapper.writeValueAsString(classifier);

        QuarkusTransaction.requiringNew().run(() ->
                operatorEventOutboxWriter.enqueue(OperatorEventType.ROTATION_OCCURRED, classifier, "postgresql")
        );

        OffsetDateTime result = operatorEventRepository.findPreviousOccurredAt(classifierJson, "postgresql");

        assertNotNull(result, "findPreviousOccurredAt must return the persisted occurredAt timestamp");
        assertTrue(result.isBefore(OffsetDateTime.now().plusSeconds(5)));
    }
}
