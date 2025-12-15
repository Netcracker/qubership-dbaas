package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import com.netcracker.cloud.dbaas.dto.backupV2.Classifier;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupExternalDatabase;

import java.util.List;

public record BackupExternalDelegate(BackupExternalDatabase backupExternalDatabase, List<Classifier> classifiers) {
}
