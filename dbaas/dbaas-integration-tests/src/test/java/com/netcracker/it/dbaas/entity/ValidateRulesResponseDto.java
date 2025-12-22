package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ValidateRulesResponseDto {
    public Map<String, String> mapLabelToPhysicalDb;
    public Map<String, String> defaultPhysicalDatabases;
}