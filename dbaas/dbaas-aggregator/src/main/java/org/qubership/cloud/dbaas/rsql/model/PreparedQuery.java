package org.qubership.cloud.dbaas.rsql.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class PreparedQuery {

    private String query;
    private Map<String, Object> parameterNamesAndValues;
}
