package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClient;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.util.Arrays;

/**
 * This service notifies all the pre-configured adapters that dbaas-aggregator is up,
 * so they start to register themselves immediately.
 */
@Slf4j
public class StartupPhysicalDbRegistrationService {

    private PhysicalDatabaseDbaasRepository physicalDatabasesRepository;

    public StartupPhysicalDbRegistrationService(PhysicalDatabaseDbaasRepository physicalDatabasesRepository, String adapterAddresses) {
        this.physicalDatabasesRepository = physicalDatabasesRepository;

        if (StringUtils.isNotBlank(adapterAddresses)) {
            Arrays.stream(adapterAddresses.split(","))
                    .forEach(address -> {
                        if (StringUtils.isNotBlank(address) && isNotRegistered(address)) {
                            notifyAdapter(address);
                        }
                    });
        }
    }

    private boolean isNotRegistered(String adapterAddress) {
        try {
            return physicalDatabasesRepository.findByAdapterAddress(adapterAddress) == null;
        } catch (Exception e) {
            log.warn("Exception while searching for adapter {} in database:", adapterAddress, e);
            return true;
        }
    }

    private void notifyAdapter(String adapterAddress) {
        try {
            DbaasAdapterRestClient restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                    .build(DbaasAdapterRestClient.class);
            Response response = restClient.forceRegistration();
            if (response.getStatus() == Response.Status.ACCEPTED.getStatusCode()) {
                log.info("Adapter {} was successfully notified about dbaas-aggregator startup.", adapterAddress);
            } else {
                log.warn("Unexpected response {} from adapter {}!", response, adapterAddress);
            }
        } catch (Exception e) {
            log.warn("Couldn't notify adapter {}, error:", adapterAddress, e);
        }
    }
}
