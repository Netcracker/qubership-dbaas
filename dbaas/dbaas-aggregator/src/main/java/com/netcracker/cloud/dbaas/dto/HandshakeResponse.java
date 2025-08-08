package org.qubership.cloud.dbaas.dto;

import java.util.Map;
import lombok.Data;

@Data
public class HandshakeResponse {
		private Map<String, String> labels;
		private String id;
}
