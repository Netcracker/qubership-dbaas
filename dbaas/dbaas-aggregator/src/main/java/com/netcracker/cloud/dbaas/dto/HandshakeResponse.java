package com.netcracker.cloud.dbaas.dto;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import java.util.Map;
import lombok.Data;

@Data
public class HandshakeResponse {
		private Map<String, String> labels;
		private String id;
}
