package com.netcracker.it.dbaas.entity.config;

import lombok.Data;

import java.util.List;

@Data
public class DeclarativeDatabaseCreationRequest {
    String apiVersion;

    String kind;

    List<DatabaseDeclaration> declarations;
}
