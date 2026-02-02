package com.netcracker.cloud.dbaas.entity.pg.backupV2;

import lombok.Data;

import java.util.List;

@Data
public class FilterCriteriaEntity {
    private List<FilterEntity> filter;
    private List<FilterEntity> exclude;
}
