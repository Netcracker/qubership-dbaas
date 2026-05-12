package com.netcracker.it.dbaas.entity;

import lombok.Data;

import java.util.List;

@Data
public class CleanupMarkedForDropRequest {
    private List<String> namespaces;
    private Boolean delete = false;
    private Boolean force = false;
}
