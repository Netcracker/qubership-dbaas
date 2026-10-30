package com.netcracker.cloud.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "Information about database - resources attached, connection info")
public class DescribedDatabase {
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    @Schema(description = "Connection properties describe how to connect to database")
    private List<Map<String, Object>> connectionProperties;
    @Schema(description = "List of resources attached to database")
    private List<DbResource> resources;
}
