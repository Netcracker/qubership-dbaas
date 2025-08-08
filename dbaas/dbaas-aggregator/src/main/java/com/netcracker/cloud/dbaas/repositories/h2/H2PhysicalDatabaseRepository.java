package org.qubership.cloud.dbaas.repositories.h2;

import org.qubership.cloud.dbaas.entity.h2.PhysicalDatabase;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Transactional
public class H2PhysicalDatabaseRepository implements PanacheRepositoryBase<PhysicalDatabase, String> {

    public List<PhysicalDatabase> findByType(String type) {
        return list("type", type);
    }

    public Optional<PhysicalDatabase> findByPhysicalDatabaseIdentifier(String physicalDatabaseId) {
        return find("physicalDatabaseIdentifier", physicalDatabaseId).firstResultOptional();
    }

    public Optional<PhysicalDatabase> findByAdapterId(String adapterId) {
        return find("adapter.adapterId like ?1", adapterId).firstResultOptional();
    }

    public Optional<PhysicalDatabase> findByAdapterAddress(String adapterAddress) {
        return find("adapter.address like ?1", adapterAddress).firstResultOptional();
    }

    public List<PhysicalDatabase> findByAdapterAddressHost(String adapterHost) {
        return list("adapter.address like ?1", '%' + adapterHost + '%');
    }

    public List<PhysicalDatabase> findByTypeAndGlobal(String type, boolean global) {
        return list("type = ?1 and global = ?2", type, global);
    }

    public boolean existsById(String id) {
        return findByIdOptional(id).isPresent();
    }

    public void merge(PhysicalDatabase db) {
        getEntityManager().merge(db);
    }

    public void merge(List<PhysicalDatabase> databases) {
        databases.forEach(this::merge);
    }
}
