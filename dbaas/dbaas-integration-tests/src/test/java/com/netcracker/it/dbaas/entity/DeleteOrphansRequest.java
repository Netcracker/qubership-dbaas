package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.List;

@Data
public class DeleteOrphansRequest {
    private List<String> namespaces;
    private Boolean delete;
}
