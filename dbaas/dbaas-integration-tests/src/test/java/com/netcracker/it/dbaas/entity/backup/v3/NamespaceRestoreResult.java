package com.netcracker.it.dbaas.entity.backup.v3;

import lombok.Data;

import java.util.List;

@Data
public class NamespaceRestoreResult {
    private List<RestoreResult> restoreResults;
    private Status status;
    private List<String> failReasons;
}
