package com.netcracker.cloud.dbaas.dto;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HttpBasicCredentials {

    @Schema(required = true, description = "Basic authentication username.")
    private String username;

    @Schema(required = true, description = "Basic authentication password.")
    private String password;
}
