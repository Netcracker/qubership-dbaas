package com.netcracker.it.dbaas.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DbState {

    private String id;
    private String state;
    private String databaseState;
}
