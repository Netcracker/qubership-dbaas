package org.qubership.cloud.dbaas.repositories.h2;

import org.qubership.cloud.dbaas.entity.h2.DatabaseRegistry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class H2DatabaseRegistryRepository implements PanacheRepositoryBase<DatabaseRegistry, UUID> {

    public Optional<DatabaseRegistry> findDatabaseRegistryByClassifierAndType(SortedMap<String, Object> classifier, String type) {
        return find("classifier = ?1 and type = ?2", classifier, type).firstResultOptional();
    }

    public List<DatabaseRegistry> findByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public void merge(DatabaseRegistry db) {
        getEntityManager().merge(db);
    }
}
