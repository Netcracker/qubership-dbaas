package org.qubership.cloud.dbaas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.dbaas.dto.role.ServiceAccountWithRoles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ServiceAccountRoles {
    private final ArrayList<ServiceAccountWithRoles> serviceAccountsWithRoles = new ArrayList<>();
    @ConfigProperty(name = "roles.yaml")
    String rawYaml;

    public ServiceAccountRoles() {
    }

    public ServiceAccountRoles(String rawRolesSecret) {
        this.rawYaml = rawRolesSecret;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
            Map<String, Object> rawServiceAccountsWithRoles = yamlReader.readValue(rawYaml, Map.class);
            for (Map.Entry<String, Object> serviceAccount : rawServiceAccountsWithRoles.entrySet()) {
                List<String> roles = (List<String>) serviceAccount.getValue();
                serviceAccountsWithRoles.add(new ServiceAccountWithRoles(serviceAccount.getKey(), roles));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse secret YAML", e);
        }
    }

    public List<String> getRolesByServiceAccountName(String serviceAccountName) {
        for (ServiceAccountWithRoles s : serviceAccountsWithRoles) {
            if (serviceAccountName.equals(s.getName())) {
                return s.getRoles();
            }
        }
        return null;
    }
}
