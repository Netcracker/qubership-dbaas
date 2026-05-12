package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.backup.v1.ExternalDatabaseStrategy;
import com.netcracker.it.dbaas.entity.backup.v1.FilterCriteria;
import com.netcracker.it.dbaas.entity.backup.v1.Mapping;
import com.netcracker.it.dbaas.entity.backup.v1.RestoreRequest;

import java.util.UUID;
import java.util.function.Consumer;

import static com.netcracker.it.dbaas.helpers.BackupHelperV1.BLOB_PATH;
import static com.netcracker.it.dbaas.helpers.BackupHelperV1.STORAGE_NAME;

public class RestoreRequestBuilder {
    private final RestoreRequest request;

    public RestoreRequestBuilder() {
        this.request = new RestoreRequest();
        this.request.setRestoreName(String.format("dbaas-autotests-%s", UUID.randomUUID()));
        this.request.setStorageName(STORAGE_NAME);
        this.request.setBlobPath(BLOB_PATH);
        this.request.setMapping(new Mapping());
        this.request.setFilterCriteria(new FilterCriteria());
        this.request.setExternalDatabaseStrategy(ExternalDatabaseStrategy.FAIL);
    }

    public RestoreRequestBuilder(RestoreRequest request) {
        this.request = request;
    }

    public RestoreRequestBuilder name(String name) {
        this.request.setRestoreName(name);
        return this;
    }

    public RestoreRequestBuilder storage(String storageName) {
        this.request.setStorageName(storageName);
        return this;
    }

    public RestoreRequestBuilder blob(String blobPath) {
        this.request.setBlobPath(blobPath);
        return this;
    }

    public RestoreRequestBuilder mapping(Mapping mapping) {
        this.request.setMapping(mapping);
        return this;
    }

    public RestoreRequestBuilder filterCriteria(FilterCriteria filterCriteria) {
        this.request.setFilterCriteria(filterCriteria);
        return this;
    }

    public RestoreRequestBuilder externalDbStrategy(ExternalDatabaseStrategy externalDatabaseStrategy) {
        this.request.setExternalDatabaseStrategy(externalDatabaseStrategy);
        return this;
    }

    public RestoreRequestBuilder filterCriteria(Consumer<FilterCriteriaBuilder> fcb) {
        var filterCriteria = new FilterCriteriaBuilder();
        fcb.accept(filterCriteria);
        this.request.setFilterCriteria(filterCriteria.build());
        return this;
    }

    public RestoreRequestBuilder mapping(Consumer<MappingBuilder> cfg) {
        var mb = new MappingBuilder();
        cfg.accept(mb);
        request.setMapping(mb.build());
        return this;
    }


    public RestoreRequest build() {
        return this.request;
    }
}
