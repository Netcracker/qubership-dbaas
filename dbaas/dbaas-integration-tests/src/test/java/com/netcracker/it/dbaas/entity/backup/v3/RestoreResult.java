package com.netcracker.it.dbaas.entity.backup.v3;

import lombok.Data;
import lombok.NonNull;

import java.util.Map;

@Data
public class RestoreResult {
    @NonNull
    private Status status = Status.PROCEEDING;
    private Map<String, String> changedNameDb;
}
