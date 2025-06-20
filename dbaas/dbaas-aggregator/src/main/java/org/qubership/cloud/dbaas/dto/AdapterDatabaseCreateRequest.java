package org.qubership.cloud.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "The request to create database for REST dbaas adapter")
public class AdapterDatabaseCreateRequest {
    private Map<String, Object> metadata;
    private String namePrefix;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> settings;
    @Deprecated
    private List<String> initScriptIdentifiers;
    private String password;
    private String username;
    private String dbName;
}
