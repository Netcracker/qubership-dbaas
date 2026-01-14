package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.Map;

@Data
public class RegisteredPhysicalDatabasesDTO {
    private Map<String, PhysicalDatabaseRegistrationResponseDTO> identified;
}