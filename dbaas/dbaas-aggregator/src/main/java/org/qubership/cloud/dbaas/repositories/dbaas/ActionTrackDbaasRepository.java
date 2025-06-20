package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;

import java.util.List;

public interface ActionTrackDbaasRepository {
    List<TrackedAction> findByCreatedTimeMsLessThan(Long ms);

    TrackedAction save(TrackedAction actionTrack);

    void deleteAll(List<TrackedAction> trackedActions);

    TrackedAction persist(TrackedAction trackedAction);
}
