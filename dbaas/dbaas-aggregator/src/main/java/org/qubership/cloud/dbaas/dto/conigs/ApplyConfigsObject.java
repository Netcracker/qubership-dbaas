package org.qubership.cloud.dbaas.dto.conigs;


import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ApplyConfigsObject {
    Map<String, List<DeclarativeConfig>> configs;
    String namespace;
}