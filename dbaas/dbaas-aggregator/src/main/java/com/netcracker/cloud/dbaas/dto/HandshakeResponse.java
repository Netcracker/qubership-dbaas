package com.netcracker.cloud.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Data;

@Data
@Schema(name = "RegistrationPhysicalRequest")
public class HandshakeResponse {
		@Schema(description = "Additional information that is sent when handshake process is done. The information is read from " +
				"file which is located on \"/app/config/dbaas.physical_databases.registration.labels.json\" directory.")
		private Map<String, String> labels;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Physical database identifier. The parameter is permanent and specified during deploy.")
		private String id;
}
