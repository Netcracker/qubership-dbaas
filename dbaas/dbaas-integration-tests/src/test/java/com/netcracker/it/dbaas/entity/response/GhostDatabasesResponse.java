package com.netcracker.it.dbaas.entity.response;

import lombok.Data;

import java.util.List;

@Data
public class GhostDatabasesResponse {
    private String physicalDatabaseId;
    private List<String> dbNames;
    private String errorMessage;
}
