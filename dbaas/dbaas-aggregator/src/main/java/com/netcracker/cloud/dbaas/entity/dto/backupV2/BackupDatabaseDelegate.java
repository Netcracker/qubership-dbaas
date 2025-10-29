package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupDatabase;

import java.util.List;
import java.util.SortedMap;


public record BackupDatabaseDelegate(BackupDatabase backupDatabase,
                                     List<SortedMap<String, Object>> classifiers) {}
