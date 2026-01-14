package com.netcracker.it.dbaas.entity;

import lombok.Data;
import lombok.NonNull;

import java.util.List;

@Data
public class LinkDatabasesRequest {
    @NonNull
    List<String> serviceNames;

    @NonNull
    String targetNamespace;
}
