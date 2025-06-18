package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class DatabaseDeclarativeConfigRepository implements PanacheRepositoryBase<DatabaseDeclarativeConfig, UUID> {

    public List<DatabaseDeclarativeConfig> findAllByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public void deleteByNamespace(String namespace) {
        delete("namespace", namespace);
    }

    public Optional<DatabaseDeclarativeConfig> findFirstByClassifierAndType(Map<String, Object> classifier, String type) {
        return find("classifier = ?1 and type = ?2", classifier, type).firstResultOptional();
    }
}
