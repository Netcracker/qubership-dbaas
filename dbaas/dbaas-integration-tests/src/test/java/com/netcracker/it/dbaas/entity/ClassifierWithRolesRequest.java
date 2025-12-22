package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ClassifierWithRolesRequest {
    private Map<String, Object> classifier;
    private String originService;
    private String userRole;
}
