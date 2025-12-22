package com.netcracker.it.dbaas.entity.response;

import com.netcracker.it.dbaas.entity.DatabaseV3;
import lombok.Data;

import java.util.List;

@Data
public class LostDatabasesResponse {
    private String physicalDatabaseId;
    private String errorMessage;
    private List<DatabaseV3> databases;
}
