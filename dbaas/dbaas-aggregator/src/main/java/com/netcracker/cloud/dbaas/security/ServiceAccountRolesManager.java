package com.netcracker.cloud.dbaas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.netcracker.cloud.dbaas.dto.role.ServiceAccountWithRoles;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@ApplicationScoped
@NoArgsConstructor
@Slf4j
public class ServiceAccountRolesManager {
    private final ArrayList<ServiceAccountWithRoles> serviceAccountsWithRoles = new ArrayList<>();

    public Set<String> getRolesByServiceAccountName(String serviceAccountName) {
        for (ServiceAccountWithRoles s : serviceAccountsWithRoles) {
            if (serviceAccountName.equals(s.getName())) {
                return s.getRoles();
            }
        }
        return null;
    }

    void onStart(@Observes StartupEvent ev, @ConfigProperty(name = "dbaas.security.k8s.service.account.roles.path") String rolesConfigPath) {
        try {
            log.info("Start kubernetes service account roles loading from {}", rolesConfigPath);
            String rawYaml = Files.readString(Path.of(rolesConfigPath));

            ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
            Map<String, Object> rawServiceAccountsWithRoles = yamlReader.readValue(rawYaml, Map.class);
            for (Map.Entry<String, Object> serviceAccount : rawServiceAccountsWithRoles.entrySet()) {
                List<String> roles = (List<String>) serviceAccount.getValue();
                serviceAccountsWithRoles.add(new ServiceAccountWithRoles(serviceAccount.getKey(), new HashSet<>(roles)));
            }
            log.info("Roles for kubernetes service accounts loaded");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse user roles secret YAML", e);
        }
    }
}
