package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.ClassifierDetails;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupDatabase;

import java.util.List;


public record BackupWithClassifiers(BackupDatabase backupDatabase,
                                    List<ClassifierDetails> classifiers) {}
