package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
@Transactional
public class PhysicalDatabasesRepository implements PanacheRepositoryBase<PhysicalDatabase, String> {
    public List<PhysicalDatabase> findByType(String type) {
        return list("type", type);
    }

    public PhysicalDatabase findByPhysicalDatabaseIdentifier(String physicalDatabaseId) {
        return find("physicalDatabaseIdentifier", physicalDatabaseId).firstResult();
    }

    public PhysicalDatabase findByAdapterId(String adapterId) {
        return find("adapter.adapterId like ?1", adapterId).firstResult();
    }

    public PhysicalDatabase findByAdapterAddress(String adapterAddress) {
        return find("adapter.address like ?1", adapterAddress).firstResult();
    }

    public List<PhysicalDatabase> findByAdapterAddressHost(String adapterHost) {
        return list("adapter.address like ?1", '%' + adapterHost + '%');
    }

    public List<PhysicalDatabase> findByTypeAndGlobal(String type, boolean global) {
        return list("type = ?1 and global = ?2", type, global);
    }

}
