package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.entity.pg.DbResource;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreatedDatabase {
    private Map<String, Object> connectionProperties;
    private List<DbResource> resources;
    private String name;
    private String adapterId;
    private ConnectionDescription connectionDescription;
}
