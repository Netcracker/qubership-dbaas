package com.netcracker.it.dbaas.entity.response;

import lombok.Data;

import java.util.List;

@Data
public class OverallStatusResponse {
    private String overallHealthStatus;
    private Integer overallLogicalDbNumber;
    private List<PhysicalDatabaseInfo> physicalDatabaseInfoList;
}
