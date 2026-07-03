package com.netcracker.cloud.dbaas.dto.backup;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NamespaceBackupDeletion {

    private List<DeleteResult> deleteResults;
    private List<String> failReasons = new ArrayList<>();
    private Status status = Status.PROCEEDING;

}
