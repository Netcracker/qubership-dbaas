package com.netcracker.it.dbaas.entity.config;

import lombok.Data;

import java.util.List;

@Data
public class ServiceRole {
    private String name;
    private List<String> roles;
}
