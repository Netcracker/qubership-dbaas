package org.qubership.cloud.dbaas.dto;

import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BulkDatabaseCreateResponse {
    private Map<String, Object> classifier;
    private String mode;
    private Response.Status creationStatus;
}
