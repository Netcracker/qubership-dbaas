package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.entity.pg.backup.TrackedAction;
import org.qubership.cloud.dbaas.repositories.dbaas.ActionTrackDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.ActionTrackRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;

import java.util.List;

@AllArgsConstructor
@ApplicationScoped
public class ActionTrackDbaasRepositoryImpl implements ActionTrackDbaasRepository {

    private ActionTrackRepository actionTrackRepository;

    @Override
    @Transactional
    public List<TrackedAction> findByCreatedTimeMsLessThan(Long ms) {
        return actionTrackRepository.findByCreatedTimeMsLessThan(ms);
    }

    @Override
    @Transactional
    public TrackedAction save(TrackedAction actionTrack) {
        if (actionTrack.getTrackId() == null) {
            actionTrackRepository.persist(actionTrack);
        } else {
            EntityManager entityManager = actionTrackRepository.getEntityManager();
            entityManager.merge(actionTrack);
        }
        return actionTrack;
    }

    @Override
    @Transactional
    public void deleteAll(List<TrackedAction> trackedActions) {
        trackedActions.forEach(actionTrackRepository::delete);
    }

    @Override
    @Transactional
    public TrackedAction persist(TrackedAction trackedAction) {
        actionTrackRepository.persist(trackedAction);
        return trackedAction;
    }
}
