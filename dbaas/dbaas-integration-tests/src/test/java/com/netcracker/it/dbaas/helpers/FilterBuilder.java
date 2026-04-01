package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.backup.v1.DatabaseKind;
import com.netcracker.it.dbaas.entity.backup.v1.Filter;

import java.util.List;


public class FilterBuilder {
    private final Filter filter;

    public FilterBuilder() {
        this.filter = new Filter();
    }

    public FilterBuilder(Filter filter) {
        this.filter = filter;
    }

    public FilterBuilder ns(String namespace) {
        this.filter.getNamespace().add(namespace);
        return this;
    }

    public FilterBuilder ms(String microserviceName) {
        this.filter.getMicroserviceName().add(microserviceName);
        return this;
    }

    public FilterBuilder dbType(String type) {
        this.filter.getDatabaseType().add(type);
        return this;
    }

    public FilterBuilder dbKind(DatabaseKind kind) {
        this.filter.getDatabaseKind().add(kind);
        return this;
    }

    public FilterBuilder ns(List<String> namespaces) {
        this.filter.setNamespace(namespaces);
        return this;
    }

    public FilterBuilder ms(List<String> microserviceNames) {
        this.filter.setMicroserviceName(microserviceNames);
        return this;
    }

    public FilterBuilder dbType(List<String> types) {
        this.filter.setDatabaseType(types);
        return this;
    }

    public FilterBuilder dbKind(List<DatabaseKind> kinds) {
        this.filter.setDatabaseKind(kinds);
        return this;
    }

    public Filter build() {
        return this.filter;
    }
}
