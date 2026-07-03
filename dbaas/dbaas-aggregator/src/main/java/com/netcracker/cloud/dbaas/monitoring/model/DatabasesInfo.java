package com.netcracker.cloud.dbaas.monitoring.model;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DatabasesInfo {
    private DatabasesInfoSegment global;
    private List<DatabasesInfoSegment> perAdapters;
}
