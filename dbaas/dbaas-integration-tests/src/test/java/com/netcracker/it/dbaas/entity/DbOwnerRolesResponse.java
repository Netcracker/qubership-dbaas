package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class DbOwnerRolesResponse {

    private List<UpdatedDatabase> succeeded = new ArrayList<>();
    private List<UpdatedDatabase> notUpdated = new ArrayList<>();


    @Data
    @AllArgsConstructor
    public static class UpdatedDatabase {
        private Map<String, Object> classifier;
        private String dbType;
    }
}
