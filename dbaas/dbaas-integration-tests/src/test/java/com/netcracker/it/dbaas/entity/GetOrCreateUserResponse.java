package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GetOrCreateUserResponse {
    private  String userId;
    private Map<String, Object> connectionProperties;

    public String getConnectionPropertyAsString(String property){
        return this.getConnectionProperties().get(property).toString();
    }
}
