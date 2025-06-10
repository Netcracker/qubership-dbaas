package org.qubership.cloud.dbaas.dto.adapter;

import org.qubership.cloud.dbaas.dto.role.PolicyRole;
import org.qubership.cloud.dbaas.dto.role.ServiceRole;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessGrantsResponse {
    @Schema(description = "Lists services roles")
    private List<ServiceRole> services;

    @Schema(description = "Lists polices roles")
    private List<PolicyRole> policies;


    @Schema(description = "Is global permissions disabled")
    private Boolean disableGlobalPermissions;
}
