package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ActionTrackRepository implements PanacheRepositoryBase<TrackedAction, String> {
    public List<TrackedAction> findByCreatedTimeMsLessThan(Long ms) {
        return list("createdTimeMs < ?1", ms);
    }
}
