package com.netcracker.it.dbaas.entity.response;

import com.netcracker.it.dbaas.entity.DatabaseV3;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MigrationResult {
    List<String> migrated = new ArrayList<>();
    List<DatabaseV3> migratedDbInfo = new ArrayList<>();
    List<String> conflicted = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    List<String> failureReasons = new ArrayList<>();
}