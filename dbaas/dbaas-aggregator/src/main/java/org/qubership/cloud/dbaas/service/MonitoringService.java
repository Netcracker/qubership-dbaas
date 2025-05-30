package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.connections.handlers.ConnectionHandlerFactory;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.monitoring.AdapterHealthStatus;
import org.qubership.cloud.dbaas.monitoring.model.*;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.MICROSERVICE_NAME;

@ApplicationScoped
@Slf4j
public class MonitoringService {
    @Inject
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Inject
    PhysicalDatabasesService physicalDatabasesService;

    @Inject
    ConnectionHandlerFactory connectionHandlerFactory;

    public List<DatabaseMonitoringEntryStatus> getDatabaseMonitoringEntryStatus() {
        List<DatabaseMonitoringEntryStatus> resultList = new ArrayList<>();
        List<DatabaseRegistry> databaseRegistries = databaseRegistryDbaasRepository.findAllDatabasesAnyLogTypeFromCache();
        List<DbaasAdapter> allAdapters = physicalDatabasesService.getAllAdapters();
        Map<String, String> adapterStatusMap = new HashMap<>();
        for (DbaasAdapter adapter : allAdapters) {
            log.debug("Adapter identifier = {} to collect health status", adapter.identifier());
            if (Boolean.TRUE.equals(adapter.isDisabled())) {
                adapterStatusMap.put(adapter.identifier(), AdapterHealthStatus.HEALTH_CHECK_STATUS_UNKNOWN);
                continue;
            }
            AdapterHealthStatus adapterHealth = adapter.getAdapterHealth();

            adapterStatusMap.put(adapter.identifier(), adapterHealth == null ? AdapterHealthStatus.HEALTH_CHECK_STATUS_UNKNOWN : adapterHealth.getStatus());
        }

        databaseRegistries.forEach(db -> {
            DatabaseMonitoringEntryStatus.DatabaseMonitoringEntryStatusBuilder monitoringBuilder = DatabaseMonitoringEntryStatus.builder();

            String host = connectionHandlerFactory.getConnectionHandler(db.getType())
                    .getHost(db.getConnectionProperties()).orElse(null);

            monitoringBuilder
                    .namespace(String.valueOf(db.getNamespace()))
                    .databaseType(String.valueOf(db.getType()))
                    .databaseName(String.valueOf(db.getName()))
                    .externallyManageable(String.valueOf(db.isExternallyManageable()))
                    .host(String.valueOf(host))
                    .status(String.valueOf(adapterStatusMap.get(db.getAdapterId())));
            if (db.getClassifier().get(MICROSERVICE_NAME) != null) {
                monitoringBuilder
                        .microservice((String) db.getClassifier().get(MICROSERVICE_NAME));
            } else if (db.getOldClassifier().get(MICROSERVICE_NAME) != null) {
                monitoringBuilder
                        .microservice((String) db.getOldClassifier().get(MICROSERVICE_NAME));
            } else {
                monitoringBuilder.microservice("null");
            }

            resultList.add(monitoringBuilder.build());
        });

        return resultList;
    }

    public DatabasesInfo getDatabasesStatus() {
        Map<String, DatabaseInfo> allReal = new HashMap<>();
        Map<String, Collection<DatabaseInfo>> perAdapterReal = new HashMap<>();

        for (DbaasAdapter adapter : physicalDatabasesService.getAllAdapters()) {
            if (Boolean.FALSE.equals(adapter.isDisabled())) {
                DbaasAdapter checkedAdapter = getAdapter(adapter.identifier()).orElseThrow(() -> new RuntimeException("Failed to find appropriate adapter"));
                Set<DatabaseInfo> adapterRealDatabases = Optional.ofNullable(checkedAdapter.getDatabases()).orElse(Collections.emptySet())
                        .stream().map(DatabaseInfo::new).collect(Collectors.toSet());
                allReal.putAll(adapterRealDatabases.stream().collect(Collectors.toMap(
                        DatabaseInfo::getName, Function.identity()
                )));
                perAdapterReal.put(checkedAdapter.type(), adapterRealDatabases);
            }
        }

        List<DatabaseInfo> markedForDrop = new ArrayList<>();
        Map<String, DatabaseInfo> registered = databaseRegistryDbaasRepository.findAllInternalDatabases().stream()
                .filter(db -> {
                    if (db.isMarkedForDrop()) {
                        markedForDrop.add(new DatabaseInfo(db.getName()));
                        return false;
                    }
                    return true;
                })
                .map(DatabaseRegistry::getName)
                .map(DatabaseInfo::new)
                .collect(Collectors.toMap(DatabaseInfo::getName, Function.identity(), (n1, n2) -> n1));

        List<DatabaseInfo> lost = registered.entrySet().stream()
                .filter(entry -> !allReal.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted()
                .collect(Collectors.toList());

        List<DatabaseInfo> ghost = allReal.entrySet().stream()
                .filter(db -> !registered.containsKey(db.getKey()))
                .map(Map.Entry::getValue)
                .sorted()
                .collect(Collectors.toList());

        log.info("In Dbaas: Registered - {}, Ghost - {}, Lost - {} databases", registered.size(), ghost.size(), lost.size());
        List<DatabasesInfoSegment> perAdapters = new ArrayList<>();

        for (Map.Entry<String, Collection<DatabaseInfo>> entry : perAdapterReal.entrySet()) {
            perAdapters.add(getAdapterDatabasesInfo(entry.getKey(), entry.getValue()));
        }

        return new DatabasesInfo(
                new DatabasesInfoSegment(
                        "all",
                        allReal.values(),
                        new DatabasesRegistrationInfo(registered.values(), lost, ghost),
                        markedForDrop
                ), perAdapters);
    }

    private DatabasesInfoSegment getAdapterDatabasesInfo(String adapterType,
                                                         Collection<DatabaseInfo> adapterReal) {
        List<DatabaseInfo> markedForDrop = new ArrayList<>();
        Map<String, DatabaseInfo> registeredInAdapter = databaseRegistryDbaasRepository.findAllInternalDatabases().stream()
                .filter(db -> db.getType().equals(adapterType))
                .filter(db -> {
                    if (db.isMarkedForDrop()) {
                        markedForDrop.add(new DatabaseInfo(db.getName()));
                        return false;
                    }
                    return true;
                })
                .map(DatabaseRegistry::getName)
                .map(DatabaseInfo::new)
                .collect(Collectors.toMap(DatabaseInfo::getName, Function.identity(), (n1, n2) -> n1));

        List<DatabaseInfo> lost = registeredInAdapter.entrySet().stream()
                .filter(entry -> !adapterReal.contains(entry.getValue()))
                .map(Map.Entry::getValue)
                .sorted()
                .collect(Collectors.toList());

        List<DatabaseInfo> ghost = adapterReal.stream()
                .filter(db -> !registeredInAdapter.containsKey(db.getName()))
                .sorted()
                .collect(Collectors.toList());
        log.info("In Dbaas {} adapter: Registered - {}, Ghost - {}, Lost - {} databases", adapterType,
                registeredInAdapter.size(), ghost.size(), lost.size());

        lost.forEach(db -> registeredInAdapter.remove(db.getName()));

        return new DatabasesInfoSegment(
                adapterType,
                adapterReal,
                new DatabasesRegistrationInfo(registeredInAdapter.values(), lost, ghost),
                markedForDrop);
    }

    private Optional<DbaasAdapter> getAdapter(String adapterId) {
        return physicalDatabasesService.getAllAdapters()
                .stream()
                .filter(dbaasAdapter -> dbaasAdapter.identifier().equals(adapterId))
                .findFirst();
    }
}
