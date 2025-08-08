package org.qubership.cloud.dbaas.dto.v3;


import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode
@ToString(callSuper = true)
@AllArgsConstructor
@RequiredArgsConstructor
public class PhysicalDatabaseRegistrationResponseDTOV3 {
    @Schema(required = true, description = "Adapter type.")
    private String type;
    @Schema(required = true, description = "Adapter identifier.")
    private String adapterId;
    @Schema(required = true, description = "Adapter address.")
    private String adapterAddress;
    @Schema(required = true, description = "If physical database is global, it is used as a default for its database type.")
    private boolean global;
    @Schema(required = true, description = "Additional information that has been sent during physical database registration.")
    private Map<String, String> labels;
    @Schema(required = true, description = "Information about features this adapter supports.")
    private Map<String, Boolean> supports;
    @Schema
    private String supportedVersion;
    @Schema
    private List<String> supportedRoles;
}
