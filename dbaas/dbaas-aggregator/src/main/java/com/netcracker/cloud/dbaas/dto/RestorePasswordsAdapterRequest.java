package org.qubership.cloud.dbaas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RestorePasswordsAdapterRequest {
    Map<String, Object> settings;
    List<Map<String, Object>> connectionProperties;
}
