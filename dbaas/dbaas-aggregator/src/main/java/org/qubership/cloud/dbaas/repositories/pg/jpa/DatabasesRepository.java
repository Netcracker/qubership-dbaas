package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class DatabasesRepository implements PanacheRepositoryBase<Database, UUID> {

    public List<Database> findByNamespace(String namespace) {
        return find("namespace", namespace).list();
    }

    public List<Database> findByNamespaceAndType(String namespace, String type) {
        return list("namespace = ?1 and type = ?2", namespace, type);
    }

    public Database findByClassifierAndType(SortedMap<String, Object> classifier, String type) {
        return find("classifier = ?1 and type = ?2", classifier, type).firstResult();
    }

    public Database findByOldClassifierAndType(SortedMap<String, Object> classifier, String type) {
        return find("oldClassifier = ?1 and type = ?2", classifier, type).firstResult();
    }

    public List<Database> findByAdapterIdAndType(String phydbid, String type) {
        return list("adapterId = ?1 and type = ?2", phydbid, type);
    }

    public Optional<Database> findByNameAndAdapterId(String name, String adapterId) {
        return find("name = ?1 and adapterId = ?2", name, adapterId).firstResultOptional();
    }

    public List<Database> findAnyLogDbTypeByName(String name) {
        return list("name", name);
    }

    public List<Database> findByDbState_DatabaseStateAndDbState_PodName(DbState.DatabaseStateStatus state, String podName) {
        return list("dbState.databaseState = ?1 and dbState.podName = ?2", state, podName);
    }

    public List<Database> findDatabaseByNamespaceAndDbState_DatabaseState(String namespace, DbState.DatabaseStateStatus state) {
        return list("namespace = ?1 and dbState.databaseState = ?2", namespace, state);
    }

    public List<Database> findByDbState_DatabaseState(DbState.DatabaseStateStatus state) {
        return list("dbState.databaseState", state);
    }

    public List<Database> findByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit) {
        return find("namespace in ?1", namespaces).range(offset, offset + limit - 1).list();
    }
}
