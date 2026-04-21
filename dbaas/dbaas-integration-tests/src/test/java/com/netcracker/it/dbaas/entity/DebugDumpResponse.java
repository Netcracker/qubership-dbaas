package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DebugDumpResponse {

    private List<DebugDatabase> logicalDatabases;
}
