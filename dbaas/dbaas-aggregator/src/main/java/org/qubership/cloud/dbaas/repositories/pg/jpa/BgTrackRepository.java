package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.BgTrack;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class BgTrackRepository implements PanacheRepositoryBase<BgTrack, UUID> {
    public Optional<BgTrack> findByNamespaceAndOperation(String namespace, String operation) {
        return find("namespace = ?1 and operation = ?2", namespace, operation).firstResultOptional();
    }

    public List<BgTrack> findAllByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public void deleteByNamespaceAndOperation(String namespace, String operation) {
        delete("namespace = ?1 and operation = ?2", namespace, operation);
    }

    public void deleteAllByNamespace(String namespace) {
        delete("namespace", namespace);
    }
}
