package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class PhysicalDatabaseRegistrationResponseDTO {
    private String type;
    private String adapterId;
    private String adapterAddress;
    private boolean global;
    private Map<String, String> labels;
    private Map<String, Boolean> supports = new HashMap<>();
}