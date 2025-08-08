package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface PhysicalDatabaseDbaasRepository {
    Stream<PhysicalDatabase> findByType(String type);

    PhysicalDatabase findByPhysicalDatabaseIdentifier(String physicalDatabaseId);

    PhysicalDatabase findByAdapterId(String adapterId);

    PhysicalDatabase findByAdapterAddress(String adapterAddress);

    List<PhysicalDatabase> findByAdapterHost(String adapterHost);

    PhysicalDatabase save(PhysicalDatabase databaseRegistration);

    List<PhysicalDatabase> findAll();

    Optional<PhysicalDatabase> findGlobalByType(String type);

    void delete(PhysicalDatabase physicalDatabase);

    void reloadH2Cache();

    void reloadH2Cache(String id);

    Object getMutex();
}
