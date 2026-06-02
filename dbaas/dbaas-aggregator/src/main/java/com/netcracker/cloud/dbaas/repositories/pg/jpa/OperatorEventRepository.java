package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.OperatorEvent;
import com.netcracker.cloud.dbaas.enums.OperatorEventStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class OperatorEventRepository implements PanacheRepositoryBase<OperatorEvent, UUID> {

    /**
     * Atomically claims up to {@code limit} pending events that are ready to be dispatched.
     * Uses SELECT FOR UPDATE SKIP LOCKED so multiple replicas can dispatch concurrently without contention.
     */
    @SuppressWarnings("unchecked")
    public List<OperatorEvent> claimPendingBatch(int limit) {
        return getEntityManager()
                .createNativeQuery(
                        "SELECT * FROM operator_event_outbox " +
                        "WHERE status = :status AND next_attempt_at <= NOW() " +
                        "ORDER BY next_attempt_at " +
                        "LIMIT :limit " +
                        "FOR UPDATE SKIP LOCKED",
                        OperatorEvent.class)
                .setParameter("status", OperatorEventStatus.PENDING.name())
                .setParameter("limit", limit)
                .getResultList();
    }

    public long deleteByStatusAndSentAtBefore(OperatorEventStatus status, OffsetDateTime cutoff) {
        return delete("status = ?1 and sentAt < ?2", status, cutoff);
    }

    public long deleteByStatusAndCreatedAtBefore(OperatorEventStatus status, OffsetDateTime cutoff) {
        return delete("status = ?1 and createdAt < ?2", status, cutoff);
    }

    /**
     * Returns the most recent occurredAt timestamp for the given (classifier, type, role) tuple,
     * used to populate previousRotatedAt in new outbox events.
     */
    public OffsetDateTime findPreviousOccurredAt(String classifierJson, String type, String role) {
        Object result = getEntityManager()
                .createNativeQuery(
                        "SELECT MAX((payload->>'occurredAt')::timestamptz) " +
                        "FROM operator_event_outbox " +
                        "WHERE status IN (:status1, :status2) " +
                        "AND payload->>'type' = :type " +
                        "AND payload->>'userRole' = :role " +
                        "AND payload->'classifier' = CAST(:classifier AS jsonb)")
                .setParameter("status1", OperatorEventStatus.SENT.name())
                .setParameter("status2", OperatorEventStatus.PENDING.name())
                .setParameter("type", type)
                .setParameter("role", role)
                .setParameter("classifier", classifierJson)
                .getSingleResult();
        if (result == null) {
            return null;
        }
        return ((java.sql.Timestamp) result).toInstant()
                .atOffset(java.time.ZoneOffset.UTC);
    }

    public long countByStatus(OperatorEventStatus status) {
        return count("status", status);
    }
}
