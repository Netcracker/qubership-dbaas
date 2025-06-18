package org.qubership.cloud.dbaas.config;

import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.service.*;
import io.quarkus.arc.All;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

@Slf4j
@Dependent
public class DbaasAggregatorConfiguration {
    public static final String POSTGRESQL = "postgresql";

    @Produces
    @Singleton
    public PasswordEncryption passwordEncryption(EncryptionServiceProvider serviceProvider) {
        return new PasswordEncryption(serviceProvider);
    }

    @Produces
    @Singleton
    public CryptoServicePasswordEncryption cryptoServicePasswordEncryption(
    ) {
        return new CryptoServicePasswordEncryption(null, null);
    }

    @Produces
    @Singleton
    public EncryptionServiceProvider encryptionServiceProvider(@All List<DataEncryption> encryptionServices) {
        return new EncryptionServiceProvider(encryptionServices);
    }

    @Produces
    @Singleton
    @Startup
    public StartupPhysicalDbRegistrationService startupPhysicalDbRegistrationService(PhysicalDatabaseDbaasRepository physicalDatabasesRepository,
                                                                                     @ConfigProperty(name = "dbaas.adapter.addresses") Optional<String> adapterAddresses) {
        return new StartupPhysicalDbRegistrationService(physicalDatabasesRepository, adapterAddresses.orElse(""));
    }
}
