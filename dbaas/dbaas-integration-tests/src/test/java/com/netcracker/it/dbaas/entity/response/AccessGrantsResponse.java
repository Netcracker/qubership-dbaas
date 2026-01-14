package com.netcracker.it.dbaas.entity.response;

import com.netcracker.it.dbaas.entity.config.PolicyRole;
import com.netcracker.it.dbaas.entity.config.ServiceRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessGrantsResponse {
    private List<ServiceRole> services;
    private List<PolicyRole> policies;
    private Boolean disableGlobalPermissions;
}
