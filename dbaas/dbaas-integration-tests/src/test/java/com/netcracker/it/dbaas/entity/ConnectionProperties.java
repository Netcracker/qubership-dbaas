package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionProperties {

    private String role;
    private Integer port;
    private String host;
    private String name;
    private String url;
    private String username;
    private String encryptedPassword;
}
