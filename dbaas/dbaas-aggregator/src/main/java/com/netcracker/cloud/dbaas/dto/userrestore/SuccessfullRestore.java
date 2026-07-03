package com.netcracker.cloud.dbaas.dto.userrestore;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SuccessfullRestore {
    private Map<String, Object> connectionProperties;
}
