package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.Map;
import java.util.SortedMap;

@Data
public class UpdateSettingsRequest {
    private SortedMap<String, Object> classifier;
    private Map<String, Object> settings;
}
