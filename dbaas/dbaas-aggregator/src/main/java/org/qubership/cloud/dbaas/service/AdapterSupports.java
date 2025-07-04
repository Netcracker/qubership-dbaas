package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Slf4j
public class AdapterSupports {

    private static final Feature FEATURE_SETTINGS = new Feature("settings", false);
    private static final Feature FEATURE_USERS = new Feature("users", true);
    private static final Feature FEATURE_DESCRIBE_DATABASES = new Feature("describeDatabases", false);
    private static final Feature FEATURE_BACKUP_RESTORE = new Feature("backupRestore", true);

    private static final Map<String, Boolean> DEFAULT_FEATURES = Stream.of(
            FEATURE_SETTINGS,
            FEATURE_USERS,
            FEATURE_DESCRIBE_DATABASES,
            FEATURE_BACKUP_RESTORE)
            .collect(Collectors.toMap(Feature::getName, Feature::isSupported));

    @NonNull
    private AbstractDbaasAdapterRESTClient client;

    @NonNull
    private String supportedVersion = "v1";
    private final ApiVersion apiVersions;

    public AdapterSupports(AbstractDbaasAdapterRESTClient client, String supportedVersion) {
        this(client, supportedVersion, null);
    }

    public AdapterSupports(AbstractDbaasAdapterRESTClient client, String supportedVersion, @Nullable ApiVersion apiVersions) {
        this.client = client;
        this.supportedVersion = supportedVersion;
        this.apiVersions = apiVersions;
    }

    public boolean settings() {
        return supports(FEATURE_SETTINGS);
    }

    public boolean users() {
        return supports(FEATURE_USERS);
    }

    public boolean describeDatabases() {
        return supports(FEATURE_DESCRIBE_DATABASES);
    }

    public boolean backupRestore() {
        return supports(FEATURE_BACKUP_RESTORE);
    }

    public Map<String, Boolean> supportedFeatures() {
        return supports(DEFAULT_FEATURES);
    }

    public boolean contract(int major, int minor) {
        if (apiVersions == null || apiVersions.getSpecs().isEmpty())
            return false;
        return apiVersions.getSpecs().stream().anyMatch(apiVersion -> apiVersion.getMajor() == major && apiVersion.getMinor() >= minor)
                || apiVersions.getSpecs().stream().anyMatch(apiVersion -> apiVersion.getSupportedMajors().contains(major) && apiVersion.getMajor() > major);
    }

    private boolean supports(Feature feature) {
        String name = feature.getName();
        boolean defaultValue = feature.isSupported();
        return supports(Collections.singletonMap(name, defaultValue)).getOrDefault(name, defaultValue);
    }

    private Map<String, Boolean> supports(Map<String, Boolean> defaults) {
        log.debug("Get supports adapter by url={}/api/{}/dbaas/adapter/{}/supports", client.adapterAddress(), supportedVersion, client.type());
        try {
            Map<String, Boolean> supports = client.sendSupportsRequest();
            return Optional.ofNullable(supports)
                    .map(it -> {
                                Map<String, Boolean> finalMap = new HashMap<>();
                                defaults.forEach((feature, defaultValue) -> {
                                    boolean finalValue = it.getOrDefault(feature, defaultValue);
                                    finalMap.put(feature, finalValue);
                                });
                                return finalMap;
                            }
                    ).orElse(defaults);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                log.debug("Request to {}/api/{}/dbaas/adapter/{}/supports returned 404, assume defaults: {}", client.adapterAddress(), supportedVersion, client.type(), defaults);
                return defaults;
            } else {
                throw e;
            }
        }
    }

    @Data
    @AllArgsConstructor
    private static class Feature {
        private String name;
        private boolean supported;
    }
}
