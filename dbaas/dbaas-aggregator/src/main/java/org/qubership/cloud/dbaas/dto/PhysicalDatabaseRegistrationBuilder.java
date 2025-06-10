package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;

import org.qubership.cloud.dbaas.dto.v3.ApiVersion;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PhysicalDatabaseRegistrationBuilder {
    private String physicalDatabaseIdentifier;
    private ExternalAdapterRegistrationEntry adapter;
    private boolean isGlobal = false;
    private String type;
    private Map<String, String> labels;
    private Boolean unidentified;
    private List<String> roles;

    private Map<String, Boolean> features;

    private String roHost;

    public PhysicalDatabaseRegistrationBuilder addPhysicalDatabaseIdentifier(String physicalDatabaseIdentifier) {
        this.physicalDatabaseIdentifier = physicalDatabaseIdentifier;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addAdapter(String id, String address, HttpBasicCredentials httpBasicCredentials, String version, ApiVersion apiVersions) {
        this.adapter = new ExternalAdapterRegistrationEntry(id, address, httpBasicCredentials, version, apiVersions);
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addType(String type) {
        this.type = type;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addLabels(Map<String, String> labels) {
        this.labels = labels;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder global(boolean isGlobal) {
        this.isGlobal = isGlobal;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addUnidentified(boolean isUnidentified) {
        this.unidentified = isUnidentified;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addRoles(List<String> roles) {
        this.roles = roles;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addFeatures(Map<String, Boolean> features) {
        this.features = features;
        return this;
    }

    public PhysicalDatabaseRegistrationBuilder addRoHost(String roHost) {
        this.roHost = roHost;
        return this;
    }

    public PhysicalDatabase build() {
        PhysicalDatabase databaseRegistration = new PhysicalDatabase();
        databaseRegistration.setId(UUID.randomUUID().toString());
        databaseRegistration.setPhysicalDatabaseIdentifier(physicalDatabaseIdentifier);
        databaseRegistration.setAdapter(adapter);
        databaseRegistration.setGlobal(isGlobal);
        databaseRegistration.setType(type);
        databaseRegistration.setLabels(labels);
        databaseRegistration.setRegistrationDate(new Date());
        databaseRegistration.setUnidentified(unidentified);
        databaseRegistration.setRoles(roles);
        databaseRegistration.setFeatures(features);
        databaseRegistration.setRoHost(roHost);
        return databaseRegistration;
    }
}
