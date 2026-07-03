package com.netcracker.cloud.dbaas.entity.pg.backupV2;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.Data;

import java.util.List;

@Data
public class FilterCriteriaEntity {
    private List<FilterEntity> include;
    private List<FilterEntity> exclude;
}
