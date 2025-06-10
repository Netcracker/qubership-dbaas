package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class DatabaseRegistryRepository implements PanacheRepositoryBase<DatabaseRegistry, UUID> {
    public Optional<DatabaseRegistry> findDatabaseRegistryByClassifierAndType(SortedMap<String, Object> classifier, String type) {
        return find("classifier = ?1 and type = ?2", classifier, type).firstResultOptional();
    }

    public List<DatabaseRegistry> findByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public void deleteOnlyTransactionalDatabaseRegistries(String namespace) {
        delete("namespace = ?1 and database.bgVersion is null", namespace);
    }

    public List<DatabaseRegistry> findAllByNamespaceAndDatabase_BgVersionNull(String namespace) {
        return list("namespace = ?1 and database.bgVersion is null", namespace);

    }

    public List<DatabaseRegistry> findAllByNamespaceAndDatabase_BgVersionNotNull(String namespace) {
        return list("namespace = ?1 and database.bgVersion is not null", namespace);
    }
}
