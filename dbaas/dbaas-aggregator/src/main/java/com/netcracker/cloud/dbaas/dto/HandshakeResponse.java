package com.netcracker.cloud.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(name = "RegistrationPhysicalRequest")
public class HandshakeResponse {
    @Schema(description = "Additional information that is sent when handshake process is done.")
		private Map<String, String> labels;
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Physical database identifier. The parameter is permanent and specified during deploy.")
		private String id;
}
