package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ResponseHelper {

    @Inject
    private PhysicalDatabasesService physicalDatabasesService;

    @Inject
    private UserService userService;

    @Inject
    private DBaaService dBaaService;

    public List<DatabaseResponseV3ListCP> toDatabaseResponse(List<DatabaseRegistry> databaseRegistries, boolean withResources) {
        Map<String, String> adapterIdToPhysId = new ConcurrentHashMap<>();
        return databaseRegistries
                .stream()
                .filter(dbr -> !dbr.getConnectionProperties().isEmpty())
                .map(dbr -> {
                    String physicalDatabaseId = dbr.getPhysicalDatabaseId();
                    if (physicalDatabaseId == null && dbr.getAdapterId() != null) {
                        physicalDatabaseId = adapterIdToPhysId.computeIfAbsent(dbr.getAdapterId(),
                                adapterId -> physicalDatabasesService.getByAdapterId(dbr.getAdapterId()).getPhysicalDatabaseIdentifier());
                    }
                    DatabaseResponseV3ListCP databaseResponseV3ListCP = new DatabaseResponseV3ListCP(dbr, physicalDatabaseId);
                    if (withResources) {
                        databaseResponseV3ListCP.setResources(dbr.getResources());
                    }
                    userService.findUsersByDatabase(dbr.getDatabase())
                            .forEach(user -> dbr.getConnectionProperties().add(user.getConnectionProperties()));
                    dBaaService.getConnectionPropertiesService().addAdditionalPropToCP(dbr);
                    return databaseResponseV3ListCP;
                })
                .toList();
    }
}
