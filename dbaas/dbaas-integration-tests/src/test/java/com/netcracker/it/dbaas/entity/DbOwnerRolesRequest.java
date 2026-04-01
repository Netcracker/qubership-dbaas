package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
public class DbOwnerRolesRequest {

    private List<Service> services;

    @Data
    @AllArgsConstructor
    public static class Service {
        private String microserviceName;
        private List<String> dbOwnerRoles;
    }

}
