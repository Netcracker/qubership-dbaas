package com.netcracker.it.dbaas.entity.response;

import lombok.Data;

@Data
public class PhysicalDatabaseInfo {
    private String physicalDatabaseId;
    private String healthStatus;
    private String logicalDbNumber;
}