package org.qubership.cloud.dbaas.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.entity.h2.DbaasUser;
import org.qubership.cloud.dbaas.repositories.h2.H2DbaasUserRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@ApplicationScoped
@Slf4j
public class DbaasUsersLoader {

    @Transactional
    void loadDbaasUser(@Observes StartupEvent event,
                       H2DbaasUserRepository usersRepository,
                       @ConfigProperty(name = "dbaas.security.users.configuration.location") String usersConfigurationLocation) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        log.info("Start user loading from {}", usersConfigurationLocation);
        InputStream usersStream = getClass().getResourceAsStream(usersConfigurationLocation);
        if (usersStream == null) {
            usersStream = FileUtils.openInputStream(FileUtils.getFile(usersConfigurationLocation));
        }
        Map<String, UserConfig> userConfiguration = objectMapper.readValue(usersStream, new TypeReference<Map<String, UserConfig>>() {
        });
        userConfiguration.forEach((username, userConfig) -> {
            DbaasUser dbaasUser = new DbaasUser();
            dbaasUser.setUsername(username);
            dbaasUser.setPassword(userConfig.getPassword());
            dbaasUser.setRoles(userConfig.getRoles());
            usersRepository.persist(dbaasUser);
        });
        log.info("{} users loaded", userConfiguration.size());
    }
}
