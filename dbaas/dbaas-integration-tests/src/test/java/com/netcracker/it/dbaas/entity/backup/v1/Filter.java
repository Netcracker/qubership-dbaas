package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class Filter {
    private List<String> namespace = new ArrayList<>();
    private List<String> microserviceName = new ArrayList<>();
    private List<DatabaseType> databaseType = new ArrayList<>();
    private List<DatabaseKind> databaseKind = new ArrayList<>();
}
