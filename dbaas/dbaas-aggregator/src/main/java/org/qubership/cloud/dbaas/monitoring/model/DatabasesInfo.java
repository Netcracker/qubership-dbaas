package org.qubership.cloud.dbaas.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class DatabasesInfo {
    private DatabasesInfoSegment global;
    private List<DatabasesInfoSegment> perAdapters;
}
