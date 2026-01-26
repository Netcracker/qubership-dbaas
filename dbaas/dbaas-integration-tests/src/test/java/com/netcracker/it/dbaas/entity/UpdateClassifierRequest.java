package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.Map;

@Data
public class UpdateClassifierRequest {
    Map<String, Object> from;
    Map<String, Object> to;
    private String OriginService;
}
