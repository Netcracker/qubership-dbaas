package com.netcracker.cloud.dbaas.dto.conigs;
import com.netcracker.cloud.dbaas.logging.StructuredLog;


import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ApplyConfigsObject {
    Map<String, List<DeclarativeConfig>> configs;
    String namespace;
}
