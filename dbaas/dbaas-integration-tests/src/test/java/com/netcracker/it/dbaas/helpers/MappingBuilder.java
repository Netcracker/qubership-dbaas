package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.backup.v1.Mapping;

import java.util.HashMap;

public class MappingBuilder {
    private final Mapping mapping;

    public MappingBuilder() {
        this.mapping = new Mapping();
    }

    public MappingBuilder(Mapping mapping) {
        this.mapping = mapping;
    }

    public MappingBuilder ns(String sourceNs, String targetNs) {
        this.mapping.getNamespaces().put(sourceNs, targetNs);
        return this;
    }

    public MappingBuilder tenant(String sourceTenant, String targetTenant) {
        this.mapping.getTenants().put(sourceTenant, targetTenant);
        return this;
    }

    public MappingBuilder ns(HashMap<String, String> namespaces) {
        this.mapping.setNamespaces(namespaces);
        return this;
    }

    public MappingBuilder tenant(HashMap<String, String> tenants) {
        this.mapping.setTenants(tenants);
        return this;
    }

    public Mapping build() {
        return this.mapping;
    }
}
