package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FilterCriteriaEntity {
    private List<FilterEntity> filter;
    private List<FilterEntity> include;
    private List<FilterEntity> exclude;
}
