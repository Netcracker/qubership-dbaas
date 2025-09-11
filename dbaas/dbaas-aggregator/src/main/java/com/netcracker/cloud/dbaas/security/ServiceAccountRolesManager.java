package com.netcracker.cloud.dbaas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.netcracker.cloud.dbaas.dto.role.ServiceAccountWithRoles;

import java.util.*;

@ApplicationScoped
public class ServiceAccountRolesManager {
    @ConfigProperty(name = "roles.yaml")
    String rawYaml;

    private final ArrayList<ServiceAccountWithRoles> serviceAccountsWithRoles = new ArrayList<>();

    public ServiceAccountRolesManager() {
    }

    public ServiceAccountRolesManager(String rawRolesSecret) {
        this.rawYaml = rawRolesSecret;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
            Map<String, Object> rawServiceAccountsWithRoles = yamlReader.readValue(rawYaml, Map.class);
            for (Map.Entry<String, Object> serviceAccount : rawServiceAccountsWithRoles.entrySet()) {
                List<String> roles = (List<String>) serviceAccount.getValue();
                serviceAccountsWithRoles.add(new ServiceAccountWithRoles(serviceAccount.getKey(), new HashSet<>(roles)));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse secret YAML", e);
        }
    }

    public Set<String> getRolesByServiceAccountName(String serviceAccountName) {
        for (ServiceAccountWithRoles s : serviceAccountsWithRoles) {
            if (serviceAccountName.equals(s.getName())) {
                return s.getRoles();
            }
        }
        return null;
    }
}
