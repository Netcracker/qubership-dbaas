package com.netcracker.it.dbaas.entity.backup.v1;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.groups.ConvertGroup;

@Data
@NoArgsConstructor
public class RestoreRequest {
    private String restoreName;
    private String storageName;
    private String blobPath;
    @Valid
    @ConvertGroup(to = RestoreGroup.class)
    private FilterCriteria filterCriteria;
    private Mapping mapping;
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy = ExternalDatabaseStrategy.FAIL;
}
