package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AggregatorHealth extends HealthItem {
    private Map<String, HealthItem> components = new HashMap<>();
}
