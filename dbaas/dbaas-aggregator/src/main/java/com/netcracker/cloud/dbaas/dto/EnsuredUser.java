package com.netcracker.cloud.dbaas.dto;

import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EnsuredUser {
    private String name;
    private Map<String, Object> connectionProperties;
    private List<DbResource> resources;
    private Boolean created;
}
