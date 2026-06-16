package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.dto.backupV2.DatabaseKind;
import com.netcracker.cloud.dbaas.dto.backupV2.Filter;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.*;

import static com.netcracker.cloud.dbaas.Constants.MICROSERVICE_NAME;
import static com.netcracker.cloud.dbaas.Constants.NAMESPACE;

@Slf4j
@ApplicationScoped
@Transactional
public class DatabaseRegistryRepository implements PanacheRepositoryBase<DatabaseRegistry, UUID> {
    public Optional<DatabaseRegistry> findDatabaseRegistryByClassifierAndType(SortedMap<String, Object> classifier, String type) {
        return find("classifier = ?1 and type = ?2", classifier, type).firstResultOptional();
    }

    public List<DatabaseRegistry> findByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public List<DatabaseRegistry> findAllByNamespaceAndDatabase_BgVersionNull(String namespace) {
        return list("namespace = ?1 and database.bgVersion is null", namespace);

    }

    public List<DatabaseRegistry> findAllByNamespaceAndDatabase_BgVersionNotNull(String namespace) {
        return list("namespace = ?1 and database.bgVersion is not null", namespace);
    }

    /**
     * Returns registries whose credentials changed (password rotation or restore) after the given cursor,
     * ordered by the marker so the caller can advance its cursor. Consumed by the operator rotation poller.
     */
    public List<DatabaseRegistry> findChangedSince(OffsetDateTime since, int limit) {
        return find("lastRotatedAt > ?1 order by lastRotatedAt", since).page(0, limit).list();
    }

    /**
     * Current high-water mark across all registries — the largest last_rotated_at, or empty when nothing
     * has rotated yet. Used to seed the operator's poll cursor without replaying history.
     */
    public Optional<OffsetDateTime> maxLastRotatedAt() {
        return Optional.ofNullable(getEntityManager()
                .createQuery("select max(dr.lastRotatedAt) from DatabaseRegistry dr", OffsetDateTime.class)
                .getSingleResult());
    }

    public List<DatabaseRegistry> findAllDatabasesByFilter(List<Filter> filters) {
        StringBuilder q = new StringBuilder(
                "SELECT cl.* " +
                        "FROM classifier cl "
        );

        int index = 0;
        boolean needDatabaseJoin = false;
        List<String> orBlock = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        for (Filter filter : filters) {
            List<String> query = new ArrayList<>();
            if (filter.getNamespace() != null && !filter.getNamespace().isEmpty()) {
                String nsValues = "nsValues" + index;
                query.add("cl.classifier #>> '{" + NAMESPACE + "}' = ANY(:" + nsValues + ")");
                params.put(nsValues, filter.getNamespace().toArray(new String[0]));
            }
            if (filter.getMicroserviceName() != null && !filter.getMicroserviceName().isEmpty()) {
                String msValues = "msValues" + index;
                query.add("cl.classifier #>> '{" + MICROSERVICE_NAME + "}' = ANY(:" + msValues + ")");
                params.put(msValues, filter.getMicroserviceName().toArray(new String[0]));
            }
            if (filter.getDatabaseType() != null && !filter.getDatabaseType().isEmpty()) {
                String typeValues = "typeValues" + index;
                query.add("cl.type = ANY(:" + typeValues + ")");
                params.put(typeValues, filter.getDatabaseType().toArray(new String[0]));
            }
            if (filter.getDatabaseKind() != null && filter.getDatabaseKind().size() == 1) {
                needDatabaseJoin = true;
                DatabaseKind kind = filter.getDatabaseKind().getFirst();
                if (kind == DatabaseKind.CONFIGURATION) {
                    query.add("d.bgversion IS NOT NULL AND d.bgversion <> '' ");
                } else if (kind == DatabaseKind.TRANSACTIONAL) {
                    query.add("(d.bgversion IS NULL OR d.bgversion = '') ");
                }
            }
            if (!query.isEmpty()) {
                String block = "(" + String.join(" AND ", query) + ")";
                orBlock.add(block);
                index++;
            }
        }

        if (needDatabaseJoin)
            q.append("LEFT JOIN database d ON cl.database_id = d.id ");

        if (!orBlock.isEmpty())
            q.append("WHERE ").append(String.join(" OR ", orBlock));

        var query = getEntityManager()
                .createNativeQuery(q.toString(), DatabaseRegistry.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
}
