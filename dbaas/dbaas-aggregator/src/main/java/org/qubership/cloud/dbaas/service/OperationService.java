package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.LinkDatabasesRequest;
import org.qubership.cloud.dbaas.dto.v3.UpdateHostRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.shared.AbstractDbResource;
import org.qubership.cloud.dbaas.exceptions.NotFoundException;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.qubership.cloud.dbaas.Constants.MICROSERVICE_NAME;

@ApplicationScoped
@Slf4j
public class OperationService {

    private final DBaaService dBaaService;
    private final PhysicalDatabasesService physicalDatabasesService;
    private final LogicalDbDbaasRepository logicalDbDbaasRepository;


    public OperationService(DBaaService dBaaService,
                            PhysicalDatabasesService physicalDatabasesService,
                            LogicalDbDbaasRepository logicalDbDbaasRepository) {
        this.dBaaService = dBaaService;
        this.physicalDatabasesService = physicalDatabasesService;
        this.logicalDbDbaasRepository = logicalDbDbaasRepository;
    }

    @Transactional
    public List<DatabaseRegistry> changeHost(List<UpdateHostRequest> updateHostRequests) {
        return updateHostRequests.stream().map(this::changeHost).toList();
    }

    @Transactional
    public DatabaseRegistry changeHost(UpdateHostRequest updateHostRequest) {
        log.info("start to update host of logical database with classifier {} and type {}. Update request {}", updateHostRequest.getClassifier(), updateHostRequest.getType(), updateHostRequest);
        DatabaseRegistry database = dBaaService.findDatabaseByClassifierAndType(updateHostRequest.getClassifier(), updateHostRequest.getType(), true);
        if (database == null) {
            throw new NotFoundException("Can't find logical database with such classifier and type");
        }
        DatabaseRegistry copyDatabase = database;
        DbaasAdapter adapterByPhysDbId = physicalDatabasesService.getAdapterByPhysDbId(updateHostRequest.getPhysicalDatabaseId());

        Database oldDb = findExistingOrphanFor(database, adapterByPhysDbId);
        if (oldDb != null) {
            log.info("Found existing suitable orphan: {}", oldDb);
            List<String> newUsers = database.getResources().stream()
                    .filter(dbResource -> dbResource.getKind().equals("user"))
                    .map(AbstractDbResource::getName)
                    .toList();
            List<DbResource> oldUnusedUsers = oldDb.getResources().stream()
                    .filter(dbResource -> dbResource.getKind().equals("user")
                            && !newUsers.contains(dbResource.getName()))
                    .toList();
            if (!oldUnusedUsers.isEmpty()) {
                log.info("Drop old unused users: {}", oldUnusedUsers);
                adapterByPhysDbId.deleteUser(oldUnusedUsers);
            }
            log.debug("Delete orphan logical DB from registry");
            List<DatabaseRegistry> databaseRegistry = oldDb.getDatabaseRegistry();
            for (int i = databaseRegistry.size() - 1; i >= 0; i--) {
                logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().deleteById(databaseRegistry.get(i).getId());
            }
        }

        if (updateHostRequest.getMakeCopy()) {
            log.info("make a copy of logical db {}", updateHostRequest.getClassifier());
            copyDatabase = dBaaService.makeCopy(database);
        }
        String originHost = (String) database.getConnectionProperties().getFirst().get("host");
        log.info("original host {}", originHost);
        if (originHost == null || originHost.isEmpty()) {
            throw new RuntimeException("can't find origin host");
        }

        updateHostInConnectionProperties(copyDatabase.getConnectionProperties(), originHost, updateHostRequest.getPhysicalDatabaseHost());
        copyDatabase.setPhysicalDatabaseId(updateHostRequest.getPhysicalDatabaseId());

        copyDatabase.setAdapterId(adapterByPhysDbId.identifier());
        if (updateHostRequest.getMakeCopy()) {
            log.debug("save previous record as orhan");
            dBaaService.markDatabasesAsOrphan(database);
            dBaaService.saveDatabaseRegistry(database);
        }
        dBaaService.saveDatabaseRegistry(copyDatabase);
        log.debug("changed database record {}", copyDatabase);
        return copyDatabase;
    }

    @Nullable
    private Database findExistingOrphanFor(DatabaseRegistry database, DbaasAdapter newAdapter) {
        Optional<Database> oldDbOptional = logicalDbDbaasRepository.getDatabaseDbaasRepository()
                .findByNameAndAdapterId(database.getName(), newAdapter.identifier());
        if (oldDbOptional.isEmpty()) return null;
        Database oldDb = oldDbOptional.get();
        if (oldDb.isMarkedForDrop()) {
            return oldDb;
        } else {
            throw new RuntimeException("There is an existing logical DB that already uses %s name: %s".formatted(database.getName(), oldDb));
        }
    }

    @Transactional
    public List<DatabaseRegistry> linkDbsToNamespace(String sourceNamespace, LinkDatabasesRequest request) {
        return logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()
                .findAnyLogDbRegistryTypeByNamespace(sourceNamespace)
                .stream()
                .filter(dbr -> request.getServiceNames().contains(dbr.getClassifier().get(MICROSERVICE_NAME)))
                .map(dbr -> dBaaService.shareDbToNamespace(dbr, request.getTargetNamespace()))
                .toList();
    }

    private void updateHostInConnectionProperties(List<Map<String, Object>> connectionProperties,
                                                  String originHost, String newHost) {
        for (Map<String, Object> property : connectionProperties) {
            for (Map.Entry<String, Object> entry : property.entrySet()) {
                Object value = entry.getValue();

                if (value instanceof String strValue) {
                    if (strValue.contains(originHost)) {
                        String newValue = strValue.replace(originHost, newHost);
                        entry.setValue(newValue);
                    }
                }
            }
        }
    }

}
