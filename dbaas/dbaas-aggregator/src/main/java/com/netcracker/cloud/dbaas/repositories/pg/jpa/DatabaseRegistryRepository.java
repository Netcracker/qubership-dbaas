package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseKind;
import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseType;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.*;

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

    public List<DatabaseRegistry> findAllDatabasesByFilter(
            List<String> namespaces,
            List<String> microserviceNames,
            List<DatabaseType> databaseTypes,
            List<DatabaseKind> databaseKinds) {

        StringBuilder q = new StringBuilder(
                "SELECT cl.* " +
                        "FROM classifier cl " +
                        "LEFT JOIN database d ON cl.database_id = d.id " +
                        "WHERE 1=1 "
        );

        Map<String, Object> params = new HashMap<>();

        if (namespaces != null && !namespaces.isEmpty()) {
            q.append("AND cl.namespace = ANY(:namespaces) ");
            params.put("namespaces", namespaces.toArray(new String[0]));
        }

        if (microserviceNames != null && !microserviceNames.isEmpty()) {
            q.append("AND cl.classifier->>'microserviceName' = ANY(:msNames) ");
            params.put("msNames", microserviceNames.toArray(new String[0]));
        }

        if (databaseTypes != null && !databaseTypes.isEmpty()) {
            q.append("AND cl.type = ANY(:types) ");
            params.put("types", databaseTypes.stream().map(DatabaseType::getType).toArray(String[]::new));
        }

        if (databaseKinds != null && databaseKinds.size() == 1) {
            DatabaseKind kind = databaseKinds.getFirst();
            if (kind == DatabaseKind.CONFIGURATION) {
                q.append("AND d.bgversion IS NOT NULL AND d.bgversion <> '' ");
            } else if (kind == DatabaseKind.TRANSACTIONAL){
                q.append("AND (d.bgversion IS NULL OR d.bgversion = '') ");
            }
        }

        var query = getEntityManager()
                .createNativeQuery(q.toString(), DatabaseRegistry.class);

        params.forEach(query::setParameter);

        return query.getResultList();
    }

}
