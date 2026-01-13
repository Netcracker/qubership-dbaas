package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HttpBasicCredentials {
    private String username;
    private String password;
}