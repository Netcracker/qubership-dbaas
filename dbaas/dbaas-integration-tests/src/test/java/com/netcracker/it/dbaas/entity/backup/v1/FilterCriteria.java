package com.netcracker.it.dbaas.entity.backup.v1;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class FilterCriteria {
    @NotNull(groups = {BackupGroup.class})
    @Size(min = 1, groups = {BackupGroup.class})
    private List<Filter> filter;
    private List<Filter> include;
    private List<Filter> exclude;
}
