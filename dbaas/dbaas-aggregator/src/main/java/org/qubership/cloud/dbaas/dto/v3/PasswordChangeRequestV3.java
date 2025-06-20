package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Map;

@EqualsAndHashCode
@ToString(callSuper = true)
@Data
public class PasswordChangeRequestV3 {

    @Schema(description = "Composite database identifier.")
    private Map<String, Object> classifier;

    @Schema(required = true, description = "Database type.")
    private String type;

    @Schema(description = "Indicates which grants should have user in connection properties", required = false)
    private String userRole;
}