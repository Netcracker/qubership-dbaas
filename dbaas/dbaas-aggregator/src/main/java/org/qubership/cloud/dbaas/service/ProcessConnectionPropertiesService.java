package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseUserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static org.qubership.cloud.dbaas.Constants.TLS;
import static org.qubership.cloud.dbaas.Constants.TLS_NOT_STRICT;

@Slf4j
@ApplicationScoped
public class ProcessConnectionPropertiesService {

    @Inject
    PhysicalDatabasesService physicalDatabasesService;
    @Inject
    DatabaseRegistryRepository databaseRegistryRepository;
    @Inject
    DatabaseUserRepository databaseUserRepository;

    @Transactional
    public void addAdditionalPropToCP(DatabaseRegistry databaseRegistry) {
        if (databaseRegistryRepository.getEntityManager().contains(databaseRegistry)) {
            databaseRegistryRepository.getEntityManager().detach(databaseRegistry);
            databaseRegistryRepository.getEntityManager().detach(databaseRegistry.getDatabase());
        }
        if (databaseRegistry.getPhysicalDatabaseId() != null) {
            PhysicalDatabase physicalDatabase = physicalDatabasesService.searchInPhysicalDatabaseCache(databaseRegistry.getPhysicalDatabaseId());
            addRoHostToConnectionProperties(databaseRegistry.getDatabase(), physicalDatabase);
            addTlsFlagToConnectionProperties(databaseRegistry.getDatabase(), physicalDatabase);
        }
    }

    @Transactional
    public void addAdditionalPropToCP(DatabaseUser user) {
        PhysicalDatabase physicalDatabase = physicalDatabasesService.searchInPhysicalDatabaseCache(user.getDatabase().getPhysicalDatabaseId());
        if (databaseUserRepository.getEntityManager().contains(user)) {
            databaseUserRepository.getEntityManager().detach(user);
        }
        addRoHostToConnectionProperties(user, physicalDatabase);
        addTlsFlagToConnectionProperties(user, physicalDatabase);
    }

    public void addTlsFlagToConnectionProperties(Database database, PhysicalDatabase physDB) {
        Map<String, Boolean> adapterSupportedFeatures = physDB.getFeatures();
        if (isTlsEnabledInAdapter(adapterSupportedFeatures)) {
            log.debug("TLS is enabled in physical database, adding tls=true flag to properties");
            database.getConnectionProperties().forEach(v -> v.put(TLS, true));
            if (isTlsNotStrictEnabledInAdapter(adapterSupportedFeatures)) {
                log.debug("tlsNotStrict is enabled in physical database, adding tlsNotStrict=true flag to properties");
                database.getConnectionProperties().forEach(v -> v.put(TLS_NOT_STRICT, true));
            }
        }
    }

    public void addTlsFlagToConnectionProperties(DatabaseUser user, PhysicalDatabase physDB) {
        Map<String, Boolean> adapterSupportedFeatures = physDB.getFeatures();
        if (isTlsEnabledInAdapter(adapterSupportedFeatures)) {
            log.debug("TLS is enabled in physical database, adding tls=true flag to properties");
            user.getConnectionProperties().put(TLS, true);
            if (isTlsNotStrictEnabledInAdapter(adapterSupportedFeatures)) {
                log.debug("tlsNotStrict is enabled in physical database, adding tlsNotStrict=true flag to properties");
                user.getConnectionProperties().put(TLS_NOT_STRICT, true);
            }
        }
    }

    private boolean isTlsEnabledInAdapter(Map<String, Boolean> adapterSupportedFeatures) {
        return adapterSupportedFeatures != null && adapterSupportedFeatures.containsKey(TLS) && adapterSupportedFeatures.get(TLS);
    }

    boolean isTlsNotStrictEnabledInAdapter(Map<String, Boolean> adapterSupportedFeatures) {
        return adapterSupportedFeatures != null && adapterSupportedFeatures.containsKey(TLS_NOT_STRICT) && adapterSupportedFeatures.get(TLS_NOT_STRICT);
    }

    public void addRoHostToConnectionProperties(Database database, PhysicalDatabase physicalDatabase) {
        String roHost = physicalDatabase.getRoHost();
        if (roHost != null && !roHost.isEmpty()) {
            database.getConnectionProperties().forEach(con -> con.put("roHost", roHost));
        }
    }

    public void addRoHostToConnectionProperties(DatabaseUser user, PhysicalDatabase physicalDatabase) {
        String roHost = physicalDatabase.getRoHost();
        if (roHost != null && !roHost.isEmpty()) {
            user.getConnectionProperties().put("roHost", roHost);
        }
    }

}
