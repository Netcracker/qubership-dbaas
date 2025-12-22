package com.netcracker.it.dbaas.entity;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class UserOperationResponse {
    private Map<String, Object> connectionProperties;
}
