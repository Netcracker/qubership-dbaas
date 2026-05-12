package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClient;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
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
        try (DbaasAdapterRestClientV2 restClientV2 = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .build(DbaasAdapterRestClientV2.class)) {
            Response response = restClientV2.forceRegistration();
            if (response.getStatus() == Response.Status.ACCEPTED.getStatusCode()) {
                log.info("Adapter {} was successfully notified about dbaas-aggregator startup via v2.", adapterAddress);
                return;
            } else if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                log.debug("V2 forceRegistration endpoint not found for the adapter{}, utilising v1 instead.", adapterAddress);
            } else {
                log.warn("Unexpected response {} from adapter {} via v2!", response, adapterAddress);
                return;
            }
        } catch (Exception e) {
            log.warn("Couldn't notify adapter {} via v2, error:", adapterAddress, e);
        }

        try (DbaasAdapterRestClient restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .build(DbaasAdapterRestClient.class)) {
            Response response = restClient.forceRegistration();   
            if (response.getStatus() == Response.Status.ACCEPTED.getStatusCode()) {
                log.info("Adapter {} was successfully notified about dbaas-aggregator startup.", adapterAddress);
            } else {
                log.warn("Unexpected response {} from adapter {}!", response, adapterAddress);
            }
        } catch (Exception e) {
            log.warn("Couldn't notify adapter {}, error: ", adapterAddress, e);
        }
    }
}
