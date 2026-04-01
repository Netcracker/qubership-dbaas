package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.Map;

@Data
public class PasswordChangeRequest {

    private Map<String, Object> classifier;
    private String type;
}
