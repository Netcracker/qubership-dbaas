package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.Map;

@Data
public class RegisteredPhysicalDatabasesDTOV3 {
    private Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identified;
}