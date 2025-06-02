package org.qubership.cloud.dbaas.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class DescribedDatabase {
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<Map<String, Object>> connectionProperties;
    private List<DbResource> resources;
}
