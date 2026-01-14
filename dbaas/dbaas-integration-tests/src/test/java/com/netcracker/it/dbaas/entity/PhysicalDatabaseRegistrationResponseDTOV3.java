package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper=true)
@AllArgsConstructor
@RequiredArgsConstructor
public class PhysicalDatabaseRegistrationResponseDTOV3 extends PhysicalDatabaseRegistrationResponseDTO {
    private String supportedVersion;
    private List<String> supportedRoles;
}
