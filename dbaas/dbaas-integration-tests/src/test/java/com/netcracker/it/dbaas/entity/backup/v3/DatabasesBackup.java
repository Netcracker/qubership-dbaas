package com.netcracker.it.dbaas.entity.backup.v3;

import lombok.Data;

import java.util.List;

@Data
public class DatabasesBackup {

    private Status status;
    private String adapterId;
    private String localId;
    private List<String> databases;
}