package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Classifier;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupDatabase;

import java.util.List;


public record BackupDatabaseDelegate(BackupDatabase backupDatabase,
                                     List<Classifier> classifiers) {}
